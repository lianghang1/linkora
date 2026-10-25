package com.linkora.server;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.serialize.PayloadSerializer;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.serialize.jackson.MessageJsonCodec;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.pipeline.AckResult;
import com.linkora.server.pipeline.AckService;
import com.linkora.server.pipeline.DeliveryCoordinator;
import com.linkora.server.pipeline.IngestListener;
import com.linkora.server.pipeline.IngestResult;
import com.linkora.server.pipeline.IngestService;
import com.linkora.server.pipeline.RegistryPushGateway;
import com.linkora.server.pipeline.SsePushGateway;
import com.linkora.server.servlet.LinkoraHttpHandler;
import com.linkora.server.servlet.LinkoraHttpResult;
import com.linkora.server.servlet.LinkoraHttpRequest;
import com.linkora.server.servlet.SseConnectionRegistry;
import com.linkora.server.servlet.SseSubscribeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Linkora 服务端编排入口。
 *
 * <p>职责：先持久化后推送、SSE 订阅鉴权与补发、ACK 状态机、上行消息摄入、
 * 重试调度与过期清理、状态查询与优雅关闭。服务端不强制另起 HTTP 服务器：
 * 宿主把 {@link #sseSubscribeHandler()} 与 {@link #sseConnectionRegistry()}
 * 接到 {@code SsePushServlet}，把 {@link #ingestHttpHandler()} 与
 * {@link #ackHttpHandler()} 接到 {@code LinkoraEndpointServlet} 即可嵌入已有容器。</p>
 *
 * <p>线程模型：所有投递/补发/扫描动作串行在单个内部调度线程上执行；
 * publish() 只做持久化（调用方线程），推送尝试异步化。</p>
 *
 * @author lianghang
 */
public final class LinkoraServer {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServer.class);

    private final ClientAuthenticator authenticator;
    private final ClientRegistry clientRegistry;
    private final MessageRepository messageRepository;
    private final DeliveryRepository deliveryRepository;
    private final ClientCursorRepository cursorRepository;
    private final PayloadSerializer payloadSerializer;
    private final MessageJsonCodec codec;
    private final ServerConfig config;
    private final SsePushGateway gateway;
    private final SseConnectionRegistry sseRegistry;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    /** 管理指令执行结果暂存（queryId → payload JSON），有界（最多 100 条，防内存增长）。 */
    private final ConcurrentHashMap<String, String> adminCommandResults =
            new ConcurrentHashMap<String, String>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 保留期清理调度器：仅 retentionDays > 0 时创建；独立线程，避免清理 DELETE 占用编排线程。 */
    private ScheduledExecutorService retentionScheduler;
    private ScheduledFuture<?> retentionTask;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> sweepTask;

    private final IngestService ingestService;
    private final AckService ackService;
    private final DeliveryCoordinator coordinator;

    private LinkoraServer(Builder builder) {
        this.authenticator = builder.authenticator;
        this.clientRegistry = builder.clientRegistry;
        this.messageRepository = builder.messageRepository;
        this.deliveryRepository = builder.deliveryRepository;
        this.cursorRepository = builder.cursorRepository;
        this.payloadSerializer = builder.payloadSerializer;
        this.codec = builder.codec;
        this.config = builder.config;
        this.sseRegistry = builder.sseRegistry != null
                ? builder.sseRegistry
                : new SseConnectionRegistry();
        this.gateway = builder.gateway != null
                ? builder.gateway
                : new RegistryPushGateway(this.sseRegistry);
        if (builder.externalScheduler != null) {
            this.scheduler = builder.externalScheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "linkora-server-orchestrator-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
            this.ownsScheduler = true;
        }
        this.coordinator = new DeliveryCoordinator(authenticator, clientRegistry, messageRepository,
                deliveryRepository, cursorRepository, codec, gateway, config, scheduler);
        this.ingestService = new IngestService(authenticator, messageRepository, codec, config,
                withAdminResultCollector(builder.ingestListeners));
        this.ackService = new AckService(authenticator, deliveryRepository, coordinator, config);
    }

    /**
     * 管理指令结果收集器：客户端执行远程查询后经上行回传的结果
     * （messageType = linkora.admin.result）暂存于有界内存区，供 getCommandResult 读取。
     * 纯旁路：只多识别一类上行消息，不影响任何既有摄入行为。
     */
    private List<IngestListener> withAdminResultCollector(List<IngestListener> listeners) {
        List<IngestListener> all = new ArrayList<>(listeners == null
                ? java.util.Collections.<IngestListener>emptyList() : listeners);
        all.add(message -> {
            if (com.linkora.core.admin.ClientCommands.RESULT_MESSAGE_TYPE
                    .equals(message.getMessageType())) {
                String payload = message.getPayloadAsUtf8String();
                adminCommandResults.put(messageIdOfResult(payload), payload);
                // 有界：最多保留 100 条，防内存无限增长
                while (adminCommandResults.size() > 100) {
                    String eldest = adminCommandResults.keySet().iterator().next();
                    adminCommandResults.remove(eldest);
                }
                log.info("收到管理指令执行结果: queryId={}", messageIdOfResult(payload));
            }
        });
        return all;
    }

    /** 从结果 payload 中取 queryId（格式约定：{"queryId":"...","clientId":"...","rows":[...]}）。 */
    private static String messageIdOfResult(String payload) {
        try {
            return com.linkora.serialize.jackson.LinkoraJsonUtil.readTree(payload)
                    .path("queryId").asText("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------- 生命周期 ----------------

    /** 启动补发/重试扫描调度。重复调用无副作用。 */
    public synchronized void start() {
        if (closed.get()) {
            throw new LinkoraException(LinkoraErrorCode.INTERNAL_ERROR, "服务端已关闭，不得重新启动");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        sweepTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                coordinator.sweep();
            }
        }, config.getSweepIntervalMs(), config.getSweepIntervalMs(), TimeUnit.MILLISECONDS);
        if (config.getRetentionDays() > 0) {
            retentionScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "linkora-retention-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
            retentionTask = retentionScheduler.scheduleWithFixedDelay(new Runnable() {
                @Override
                public void run() {
                    runRetentionCleanup();
                }
            }, 60_000L, 24L * 60 * 60 * 1000L, TimeUnit.MILLISECONDS);
        }
        log.info("Linkora 服务端已启动（扫描周期 {} ms，数据保留 {} 天）",
                config.getSweepIntervalMs(),
                config.getRetentionDays() > 0 ? String.valueOf(config.getRetentionDays()) : "不限");
    }

    /** 优雅关闭：停止扫描、断开全部 SSE 连接、释放调度线程。幂等。 */
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        ScheduledFuture<?> task = sweepTask;
        if (task != null) {
            task.cancel(false);
        }
        try {
            gateway.closeAll();
        } catch (RuntimeException e) {
            log.warn("关闭 SSE 连接时出现异常", e);
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
        if (retentionScheduler != null) {
            retentionScheduler.shutdownNow();
        }
        log.info("Linkora 服务端已关闭");
    }

    public boolean isRunning() {
        return running.get();
    }

    // ---------------- 发布（线上 → 线下） ----------------

    /** 发布字节负载消息（不过期、无链路追踪）。 */
    public PublishResult publish(String clientId, String messageType, byte[] payload) {
        return publish(clientId, messageType, payload, null, null);
    }

    /**
     * 发布带顺序键的消息：同一 clientId 下，同 key 的消息严格按发布顺序投递
     * （前一条到终态之前不推后一条）；key 为 null 时退化为"尽力投递"。
     *
     * @param sequenceKey 顺序键（如订单号、账户号、设备号），≤128 字符；可为 null
     */
    public PublishResult publish(String clientId, String messageType, byte[] payload,
                                 String sequenceKey) {
        return publish(clientId, messageType, payload, null, null, sequenceKey);
    }

    /**
     * 发布字节负载消息：持久化优先，返回"已接受"后异步尝试 SSE 推送。
     *
     * @param expiresAt 可为 null（不过期）
     */
    public PublishResult publish(String clientId, String messageType, byte[] payload,
                                 Long expiresAt, String traceId) {
        return publish(clientId, messageType, payload, expiresAt, traceId, null);
    }

    /** 完整发布入口。 */
    public PublishResult publish(String clientId, String messageType, byte[] payload,
                                 Long expiresAt, String traceId, String sequenceKey) {
        ensureRunning();
        long now = System.currentTimeMillis();
        LinkoraMessage message;
        try {
            message = LinkoraMessage.builder()
                    .messageId(UUID.randomUUID().toString())
                    .clientId(clientId)
                    .messageType(messageType)
                    .payload(payload)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .schemaVersion(com.linkora.core.protocol.Protocol.PROTOCOL_VERSION)
                    .traceId(traceId)
                    .sequenceKey(sequenceKey)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, e.getMessage(), e);
        }
        return publish(message);
    }

    /** 发布对象负载消息（需要构建时提供 payloadSerializer）。 */
    public PublishResult publish(String clientId, String messageType, Object payload) {
        return publish(clientId, messageType, payload, null);
    }

    /** 发布对象负载 + 顺序键。 */
    public PublishResult publish(String clientId, String messageType, Object payload,
                                 String sequenceKey) {
        if (payloadSerializer == null) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "未配置 payloadSerializer，无法序列化对象负载");
        }
        return publish(clientId, messageType, payloadSerializer.serialize(payload),
                null, null, sequenceKey);
    }

    /**
     * 发布预构建消息：幂等持久化 + 投递记录 + 异步首推。
     * 消息目标客户端必须已注册且启用。
     */
    public PublishResult publish(LinkoraMessage message) {
        ensureRunning();
        requireRegisteredClient(message.getClientId());
        boolean duplicate = messageRepository.findByMessageId(message.getMessageId()).isPresent();
        if (duplicate) {
            log.debug("消息重复发布（幂等命中）: messageId={}", message.getMessageId());
            DeliveryRecord record = deliveryRepository
                    .find(message.getMessageId(), message.getClientId()).orElse(null);
            if (record == null) {
                // 崩溃窗口兜底：首次发布在 save 与 init 之间崩溃会留下
                // "已持久化但无投递记录"的孤儿消息；此处补建投递记录，
                // 使发布方按幂等重试即可恢复投递（seq 此刻才分配，无重复风险）。
                log.warn("发现孤儿消息（无投递记录），补建投递记录: messageId={}, clientId={}",
                        message.getMessageId(), message.getClientId());
                long now = System.currentTimeMillis();
                long seq = deliveryRepository.nextDeliverySeq(message.getClientId());
                record = new DeliveryRecord(message.getMessageId(), message.getClientId(),
                        seq, com.linkora.core.message.MessageState.ACCEPTED, 0, null, null, now, now,
                        message.getSequenceKey());
                deliveryRepository.init(record);
            } else {
                log.debug("重复发布触发重投: messageId={}", message.getMessageId());
            }
            coordinator.pushNow(message.getClientId(), message.getMessageId(), record.getSeq());
            return new PublishResult(message.getMessageId(), record.getSeq(), true);
        }
        messageRepository.save(message);
        long seq = deliveryRepository.nextDeliverySeq(message.getClientId());
        long now = System.currentTimeMillis();
        deliveryRepository.init(new DeliveryRecord(message.getMessageId(), message.getClientId(),
                seq, com.linkora.core.message.MessageState.ACCEPTED, 0, null, null, now, now,
                message.getSequenceKey()));
        coordinator.pushNow(message.getClientId(), message.getMessageId(), seq);
        log.debug("消息已持久化接受并调度推送: messageId={}, clientId={}, seq={}",
                message.getMessageId(), message.getClientId(), seq);
        return new PublishResult(message.getMessageId(), seq, false);
    }

    // ---------------- 查询 ----------------

    /** 查询消息状态（正文 + 各目标投递状态）。 */
    public Optional<MessageDeliveryStatus> getMessageStatus(String messageId) {
        Optional<LinkoraMessage> message = messageRepository.findByMessageId(messageId);
        if (!message.isPresent()) {
            return Optional.empty();
        }
        List<DeliveryRecord> records = deliveryRepository.findAllByMessageId(messageId);
        return Optional.of(new MessageDeliveryStatus(message.get(), records));
    }

    /** 查询客户端积压与在线状态。 */
    public BacklogInfo getBacklog(String clientId) {
        return new BacklogInfo(clientId,
                deliveryRepository.countUnfinished(clientId),
                gateway.isConnected(clientId));

    }

    // ---------------- 管理/观测接口（人类可读视图） ----------------

    /**
     * 全部客户端概览：谁在线、谁压着消息、谁有死信。
     *
     * <p>时间已格式化为 {@code yyyy-MM-dd HH:mm:ss}，不含裸时间戳。</p>
     */
    public List<com.linkora.server.admin.ClientOverviewView> getClientOverviews() {
        List<com.linkora.server.admin.ClientOverviewView> result = new ArrayList<>();
        for (ClientRegistration registration : clientRegistry.findAll()) {
            result.add(new com.linkora.server.admin.ClientOverviewView(
                    registration.getClientId(),
                    registration.isEnabled(),
                    gateway.connectionCount(registration.getClientId()),
                    (int) deliveryRepository.countUnfinished(registration.getClientId()),
                    deliveryRepository.countDeadLetters(registration.getClientId()),
                    registration.getCreatedAt()));
        }
        return result;
    }

    /**
     * 指定客户端的未终结投递明细（含死信，供人工介入），按 seq 升序。
     *
     * <p>时间已格式化、payload 为 UTF-8 文本预览（二进制只标注字节数）。</p>
     *
     * @param limit 上限（1~500，越界自动收敛）
     */
    public List<com.linkora.server.admin.DeliveryView> listUnfinishedDeliveries(
            String clientId, int limit) {
        requireNotBlank(clientId, "clientId");
        List<com.linkora.server.admin.DeliveryView> result = new ArrayList<>();
        for (DeliveryRecord record : deliveryRepository.findUnsettled(clientId, clampLimit(limit))) {
            result.add(toView(record));
        }
        return result;
    }

    /**
     * 指定客户端的死信列表（进入死信的原因在 lastError 中），按最近更新倒序。
     */
    public List<com.linkora.server.admin.DeliveryView> listDeadLetters(String clientId, int limit) {
        requireNotBlank(clientId, "clientId");
        List<com.linkora.server.admin.DeliveryView> result = new ArrayList<>();
        for (DeliveryRecord record : deliveryRepository.findDeadLetters(clientId, clampLimit(limit))) {
            result.add(toView(record));
        }
        return result;
    }

    /**
     * 强制断开指定客户端的全部 SSE 连接（处置"假在线"连接）。
     * 客户端会自动重连并按 Last-Event-ID 补发，不丢数据。
     *
     * @return 断开的连接数（0 表示该客户端本就不在线）
     */
    public int disconnectClient(String clientId) {
        requireNotBlank(clientId, "clientId");
        int closed = gateway.disconnectClient(clientId);
        log.info("管理接口断开客户端连接: clientId={}, closed={}", clientId, closed);
        return closed;
    }

    /**
     * 人工重放死信：DEAD_LETTER → RETRYING，并立即尝试推送。
     *
     * @return true 表示重放成功；false 表示记录不存在或不在死信状态
     */
    public boolean retryDeadLetter(String clientId, String messageId) {
        requireNotBlank(clientId, "clientId");
        requireNotBlank(messageId, "messageId");
        DeliveryRecord record = deliveryRepository.find(messageId, clientId).orElse(null);
        if (record == null || record.getState() != com.linkora.core.message.MessageState.DEAD_LETTER) {
            return false;
        }
        boolean moved = deliveryRepository.compareAndSetState(messageId, clientId,
                com.linkora.core.message.MessageState.DEAD_LETTER,
                com.linkora.core.message.MessageState.RETRYING,
                "人工重放（管理接口）", null,
                record.getRetryCount(), record.getRetryCount());
        if (moved) {
            log.info("管理接口重放死信: messageId={}, clientId={}", messageId, clientId);
            coordinator.pushNow(clientId, messageId, record.getSeq());
        }
        return moved;
    }

    /**
     * 人工跳过一条卡住的消息：当前状态 → EXPIRED（终态），同键后续消息立即放行。
     *
     * @return true 表示跳过成功；false 表示记录不存在或已是终态
     */
    public boolean skipMessage(String clientId, String messageId) {
        requireNotBlank(clientId, "clientId");
        requireNotBlank(messageId, "messageId");
        DeliveryRecord record = deliveryRepository.find(messageId, clientId).orElse(null);
        if (record == null || record.getState().isTerminal()) {
            return false;
        }
        boolean moved = deliveryRepository.compareAndSetState(messageId, clientId,
                record.getState(), com.linkora.core.message.MessageState.EXPIRED,
                "人工跳过（管理接口）", null,
                record.getRetryCount(), record.getRetryCount());
        if (moved) {
            log.info("管理接口跳过消息: messageId={}, clientId={}, 原状态={}",
                    messageId, clientId, record.getState());
            coordinator.notifyDelivered(messageId, clientId);
        }
        return moved;
    }

    private com.linkora.server.admin.DeliveryView toView(DeliveryRecord record) {
        com.linkora.core.message.LinkoraMessage message =
                messageRepository.findByMessageId(record.getMessageId()).orElse(null);
        byte[] payload = message == null ? null : message.getPayload();
        String messageType = message == null ? "（正文缺失）" : message.getMessageType();
        return new com.linkora.server.admin.DeliveryView(
                record.getMessageId(), record.getClientId(), messageType, record.getSeq(),
                record.getSequenceKey(), record.getState(), record.getRetryCount(),
                record.getNextRetryAt(), record.getLastError(), payload,
                record.getCreatedAt(), record.getUpdatedAt());
    }

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(500, limit));
    }

    private static void requireNotBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }

    // ---------------- 管理/观测接口（第二批：零风险扩展） ----------------

    /**
     * 消息列表：按条件筛选 + 分页（created_at 倒序），附带各目标投递状态摘要。
     *
     * <p>全部过滤条件可为 null；时间参数为 epoch 毫秒（含边界）。
     * 无 clientId 的全局查询在大表上可能全表扫描，建议至少带 clientId。</p>
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数（1~100）
     */
    public List<com.linkora.server.admin.MessageView> listMessages(String clientId,
                                                                   String messageType,
                                                                   Long beginTime, Long endTime,
                                                                   int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<LinkoraMessage> messages = messageRepository.listMessages(
                trimToNull(clientId), trimToNull(messageType),
                beginTime, endTime, (safePage - 1) * safeSize, safeSize);
        List<com.linkora.server.admin.MessageView> result = new ArrayList<>();
        for (LinkoraMessage message : messages) {
            Object[] agg = aggregateDelivery(message);
            result.add(new com.linkora.server.admin.MessageView(message,
                    deliverySummary(message.getMessageId()),
                    (String) agg[0], (String) agg[1], (Integer) agg[2],
                    (String) agg[3], (String) agg[4]));
        }
        return result;
    }

    /**
     * 聚合一条消息的全部投递记录为最需关注的状态：
     * 死信 &gt; 重试中 &gt; 失败 &gt; 已过期 &gt; 推进中（CREATED/ACCEPTED/RECEIVED）&gt; 已完成。
     * 无投递记录 = 上行消息（客户端发布，服务端直接接收，无投递语义），
     * 返回合成状态 UPLINK，避免误显示为"已创建/未知"。
     *
     * @return [state 名, 中文描述, 最大重试次数, 最近错误, 最近推进时间（格式化）]
     */
    private Object[] aggregateDelivery(LinkoraMessage message) {
        List<DeliveryRecord> records = deliveryRepository.findAllByMessageId(message.getMessageId());
        if (records.isEmpty()) {
            return new Object[]{"UPLINK", "上行消息 · 服务端已接收", 0, "—",
                    com.linkora.server.admin.AdminViews.formatTime(message.getCreatedAt())};
        }
        MessageState worst = null;
        int worstPriority = -1;
        int retryCount = 0;
        String lastError = "—";
        long latestUpdatedAt = 0;
        for (DeliveryRecord record : records) {
            int priority = deliveryAttentionPriority(record.getState());
            if (priority > worstPriority) {
                worstPriority = priority;
                worst = record.getState();
            }
            retryCount = Math.max(retryCount, record.getRetryCount());
            if (record.getLastError() != null && !record.getLastError().trim().isEmpty()) {
                lastError = record.getLastError();
            }
            latestUpdatedAt = Math.max(latestUpdatedAt, record.getUpdatedAt());
        }
        MessageState state = worst == null ? MessageState.CREATED : worst;
        return new Object[]{state.name(), com.linkora.server.admin.AdminViews.stateText(state),
                retryCount, lastError, com.linkora.server.admin.AdminViews.formatTime(latestUpdatedAt)};
    }

    private int deliveryAttentionPriority(MessageState state) {
        switch (state) {
            case DEAD_LETTER: return 7;
            case RETRYING:    return 6;
            case FAILED:      return 5;
            case EXPIRED:     return 4;
            case CREATED:     return 3;
            case ACCEPTED:    return 2;
            case RECEIVED:    return 1;
            case PROCESSED:   return 0;
            default:          return 0;
        }
    }

    /**
     * 消息完整详情：完整 payload 明文（二进制只标注字节数）+ 全部投递记录。
     *
     * @return 消息不存在时返回 null
     */
    public com.linkora.server.admin.MessageDetailView getMessageDetail(String messageId) {
        requireNotBlank(messageId, "messageId");
        LinkoraMessage message = messageRepository.findByMessageId(messageId).orElse(null);
        if (message == null) {
            return null;
        }
        List<com.linkora.server.admin.DeliveryView> deliveries = new ArrayList<>();
        for (DeliveryRecord record : deliveryRepository.findAllByMessageId(messageId)) {
            deliveries.add(toView(record));
        }
        return new com.linkora.server.admin.MessageDetailView(message, deliveries);
    }

    /**
     * 人工状态变更：把一条投递记录从当前状态变更为任意<b>合法</b>目标状态
     * （合法性由状态机 {@code canTransitionTo} 校验）。
     *
     * <p>{@code retryDeadLetter} / {@code skipMessage} 是本方法的便捷特例。
     * reason 会写入 lastError 留痕（格式：{@code 人工变更: <reason>}）。</p>
     *
     * @param target 目标状态（等于当前状态视为无效操作返回 false）
     * @return true 表示变更成功；false 表示记录不存在或目标状态等于当前状态
     * @throws IllegalArgumentException 非法状态转移
     */
    public boolean forceTransition(String clientId, String messageId,
                                   com.linkora.core.message.MessageState target, String reason) {
        requireNotBlank(clientId, "clientId");
        requireNotBlank(messageId, "messageId");
        requireNotBlank(reason, "reason");
        if (target == null) {
            throw new IllegalArgumentException("target 不能为 null");
        }
        DeliveryRecord record = deliveryRepository.find(messageId, clientId).orElse(null);
        if (record == null) {
            return false;
        }
        if (record.getState() == target) {
            return false;
        }
        if (!record.getState().canTransitionTo(target)) {
            throw new IllegalArgumentException("非法状态转移: " + record.getState()
                    + " → " + target + "（当前记录 " + messageId + "）");
        }
        boolean moved = deliveryRepository.compareAndSetState(messageId, clientId,
                record.getState(), target, "人工变更: " + reason, null,
                record.getRetryCount(), record.getRetryCount());
        if (moved) {
            log.info("管理接口人工状态变更: messageId={}, clientId={}, {} → {}, 原因: {}",
                    messageId, clientId, record.getState(), target, reason);
            if (target == com.linkora.core.message.MessageState.RETRYING) {
                coordinator.pushNow(clientId, messageId, record.getSeq());
            } else {
                coordinator.notifyDelivered(messageId, clientId);
            }
        }
        return moved;
    }

    /**
     * 同键阻塞统计：同客户端、同顺序键下，除指定消息外仍处于非终态的投递条数。
     * 消息无顺序键或记录不存在时返回 0。仅供运维界面提示，不参与投递链路。
     */
    public int countBlockedBySequenceKey(String clientId, String messageId) {
        requireNotBlank(clientId, "clientId");
        requireNotBlank(messageId, "messageId");
        DeliveryRecord record = deliveryRepository.find(messageId, clientId).orElse(null);
        if (record == null || record.getSequenceKey() == null
                || record.getSequenceKey().trim().isEmpty()) {
            return 0;
        }
        int total = deliveryRepository.countUnfinishedBySequenceKey(clientId, record.getSequenceKey());
        return Math.max(0, total - 1);
    }

    /** 管理接口：注册新客户端（白名单添加）。已存在时返回 false。 */
    public boolean registerClient(String clientId, boolean enabled) {
        requireNotBlank(clientId, "clientId");
        String id = clientId.trim();
        if (clientRegistry.findByClientId(id).isPresent()) {
            return false;
        }
        clientRegistry.register(new ClientRegistration(id, enabled, System.currentTimeMillis()));
        log.info("管理接口注册客户端: clientId={}, enabled={}", id, enabled);
        return true;
    }

    /**
     * 管理接口：注销客户端（移出白名单）。历史消息与投递数据保留；注销前应先断开其连接。
     *
     * @return true 表示注销成功；false 表示客户端不存在或仓储不支持注销
     */
    public boolean removeClient(String clientId) {
        requireNotBlank(clientId, "clientId");
        boolean ok = clientRegistry.removeClient(clientId.trim());
        if (ok) {
            log.info("管理接口注销客户端: clientId={}", clientId.trim());
        }
        return ok;
    }

    /** 运行时启用客户端（恢复收发）。 */
    public boolean enableClient(String clientId) {
        requireNotBlank(clientId, "clientId");
        boolean ok = clientRegistry.setEnabled(clientId, true);
        if (ok) {
            log.info("管理接口启用客户端: clientId={}", clientId);
        }
        return ok;
    }

    /** 运行时禁用客户端（拒收新消息、拒绝接入；恢复用 enableClient）。 */
    public boolean disableClient(String clientId) {
        requireNotBlank(clientId, "clientId");
        boolean ok = clientRegistry.setEnabled(clientId, false);
        if (ok) {
            log.info("管理接口禁用客户端: clientId={}", clientId);
        }
        return ok;
    }

    /**
     * 批量重放死信（逐条走 {@link #retryDeadLetter}）。
     *
     * @return 成功重放的条数
     */
    public int retryAllDeadLetters(String clientId, int limit) {
        requireNotBlank(clientId, "clientId");
        int success = 0;
        for (DeliveryRecord record : deliveryRepository.findDeadLetters(clientId, clampLimit(limit))) {
            if (retryDeadLetter(clientId, record.getMessageId())) {
                success++;
            }
        }
        return success;
    }

    /**
     * 批量跳过死信（逐条走 {@link #skipMessage}，全部标记 EXPIRED 留痕）。
     *
     * @return 成功跳过的条数
     */
    public int skipAllDeadLetters(String clientId, int limit) {
        requireNotBlank(clientId, "clientId");
        int success = 0;
        for (DeliveryRecord record : deliveryRepository.findDeadLetters(clientId, clampLimit(limit))) {
            if (skipMessage(clientId, record.getMessageId())) {
                success++;
            }
        }
        return success;
    }

    /**
     * 运营统计（人类可读）：最近 N 天每日发布量 + 全部投递记录的状态分布。
     */
    public com.linkora.server.admin.DailyStatsView getDailyStats(int days) {
        List<com.linkora.server.admin.DailyStatsView.DayPublish> perDay = new ArrayList<>();
        for (Map.Entry<String, Long> entry
                : messageRepository.countPublishedPerDay(Math.max(1, Math.min(90, days))).entrySet()) {
            perDay.add(new com.linkora.server.admin.DailyStatsView.DayPublish(
                    entry.getKey(), entry.getValue()));
        }
        Map<String, Long> distribution = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : deliveryRepository.countByState().entrySet()) {
            try {
                distribution.put(AdminStateText.text(entry.getKey()), entry.getValue());
            } catch (IllegalArgumentException unknownState) {
                // 未知状态名（历史数据）按原样输出
                distribution.put(entry.getKey(), entry.getValue());
            }
        }
        return new com.linkora.server.admin.DailyStatsView(perDay, distribution);
    }

    private String deliverySummary(String messageId) {
        StringBuilder sb = new StringBuilder();
        for (DeliveryRecord record : deliveryRepository.findAllByMessageId(messageId)) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(record.getClientId()).append(':')
                    .append(com.linkora.server.admin.AdminViews.stateText(record.getState()));
        }
        return sb.length() > 0 ? sb.toString() : "（无投递记录）";
    }

    /** 状态名的中文描述（供统计分布使用）。 */
    private static final class AdminStateText {
        private static String text(String stateName) {
            return com.linkora.server.admin.AdminViews.stateText(
                    com.linkora.core.message.MessageState.valueOf(stateName));
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    // ---------------- 管理指令：远程触发客户端运营操作（ctrl 事件，不落库） ----------------

    /**
     * 向客户端下发管理指令（仅在线实时生效；不落库、不重试、不混业务消息，
     * 未送达即返回 0，由运维再次触发）。指令白名单见
     * {@link com.linkora.core.admin.ClientCommands}。
     *
     * @param params 指令参数（messageId / reason / queryId 等），可为 null
     * @return 成功送达的连接数（0 表示客户端当前不在线）
     */
    public int sendClientCommand(String clientId, String command,
                                 Map<String, Object> params) {
        requireNotBlank(clientId, "clientId");
        requireNotBlank(command, "command");
        if (!com.linkora.core.admin.ClientCommands.isKnown(command)) {
            throw new IllegalArgumentException("未知管理指令: " + command);
        }
        com.fasterxml.jackson.databind.node.ObjectNode node =
                com.linkora.serialize.jackson.LinkoraJsonUtil.getObjectMapper().createObjectNode();
        node.put("cmd", command);
        node.set("params", com.linkora.serialize.jackson.LinkoraJsonUtil.getObjectMapper()
                .valueToTree(params == null
                        ? java.util.Collections.<String, Object>emptyMap() : params));
        String json = node.toString();
        int delivered = gateway.sendControl(clientId, json);
        if (delivered == 0) {
            log.warn("管理指令未送达（客户端离线）: clientId={}, command={}", clientId, command);
        } else {
            log.info("管理指令已下发: clientId={}, command={}, delivered={}", clientId, command, delivered);
        }
        return delivered;
    }

    /** 远程触发客户端重发指定上行死信。 */
    public int retryClientOutboxDeadLetter(String clientId, String messageId) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("messageId", messageId);
        return sendClientCommand(clientId,
                com.linkora.core.admin.ClientCommands.RETRY_OUTBOX_DEAD_LETTER, params);
    }

    /** 远程触发客户端放弃指定上行死信（reason 留痕于客户端）。 */
    public int discardClientOutboxDeadLetter(String clientId, String messageId, String reason) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("messageId", messageId);
        params.put("reason", reason == null ? "—" : reason);
        return sendClientCommand(clientId,
                com.linkora.core.admin.ClientCommands.DISCARD_OUTBOX_DEAD_LETTER, params);
    }

    /** 探测客户端指令通道连通性。 */
    public int pingClient(String clientId) {
        return sendClientCommand(clientId,
                com.linkora.core.admin.ClientCommands.PING, null);
    }

    /**
     * 远程查询客户端本地 outbox：下发 QUERY_OUTBOX 指令，客户端执行后经上行回传结果。
     *
     * @param status 目标状态名（OutboxStatus，null = 全部）
     * @return queryId（用 {@link #getCommandResult(String)} 读取结果；客户端离线时结果不会产生）
     */
    public String queryClientOutbox(String clientId, String status, int limit) {
        requireNotBlank(clientId, "clientId");
        String queryId = UUID.randomUUID().toString();
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("queryId", queryId);
        params.put("status", status);
        params.put("limit", limit);
        sendClientCommand(clientId,
                com.linkora.core.admin.ClientCommands.QUERY_OUTBOX, params);
        return queryId;
    }

    /**
     * 读取远程查询结果（queryId 对应的完整 JSON）。
     *
     * @return 结果 JSON；null 表示尚未收到（客户端未执行/仍在途/已淘汰）
     */
    public String getCommandResult(String queryId) {
        requireNotBlank(queryId, "queryId");
        return adminCommandResults.get(queryId);
    }

    // ---------------- 宿主接入 ----------------

    /** SSE 订阅处理器（接到 SsePushServlet）。 */
    public SseSubscribeHandler sseSubscribeHandler() {
        return coordinator;
    }

    /**
     * 单个上行请求体的最大字节数，即 {@link ServerConfig#getMessageSizeLimitBytes()}。
     *
     * <p>这是**唯一默认值来源**：Servlet 层不再自带默认值，注册时必须显式传入本值，
     * 避免"配置了 4MB 却被 Servlet 的 2MB 硬上限拦下"这类不一致。</p>
     */
    public long getMaxBodyBytes() {
        return config.getMessageSizeLimitBytes();
    }

    /** 每连接 SSE 出站队列字节水位（透传 ServerConfig，供宿主装配 Servlet 时使用）。 */
    public long getMaxOutboundBytesPerConnection() {
        return config.getMaxOutboundBytesPerConnection();
    }

    /**
     * 立即执行一次保留期清理（也可由 retentionDays > 0 时的每日调度自动触发）。
     * 清理范围与豁免规则见 {@code DeliveryRepository#cleanupRetained}：
     * 仅删除已终态（PROCESSED/EXPIRED）且超期的记录；死信与非终态保留。
     *
     * @return 删除的总行数；retentionDays = 0（未启用）时返回 0
     */
    public int cleanupOnce() {
        if (config.getRetentionDays() <= 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis()
                - config.getRetentionDays() * 24L * 60 * 60 * 1000L;
        return deliveryRepository.cleanupRetained(cutoff);
    }

    private void runRetentionCleanup() {
        try {
            int removed = cleanupOnce();
            if (removed > 0) {
                log.info("保留期清理完成：删除 {} 行（保留期 {} 天）",
                        removed, config.getRetentionDays());
            }
        } catch (RuntimeException e) {
            // 清理失败不影响投递主链路，等待下一个周期重试
            log.warn("保留期清理失败，将在下一周期重试", e);
        }
    }

    /** SSE 连接注册表（接到 SsePushServlet；使用默认网关时非 null）。 */
    public SseConnectionRegistry sseConnectionRegistry() {
        return sseRegistry;
    }

    /** 上行消息接入 HTTP handler（接到 LinkoraEndpointServlet，映射 /messages）。 */
    public LinkoraHttpHandler ingestHttpHandler() {
        return new LinkoraHttpHandler() {
            @Override
            public LinkoraHttpResult handle(LinkoraHttpRequest request) {
                IngestResult result = ingestService.ingest(toAuthRequest(request));
                if (result.getStatus() == IngestResult.Status.REJECTED) {
                    return jsonResult(result.httpStatus(),
                            codec.encodeErrorResponse(result.getErrorCode(), result.getErrorMessage()));
                }
                return jsonResult(200, codec.encodeIngestResponse(
                        result.getStatus() == IngestResult.Status.DUPLICATE, result.getMessageId()));
            }
        };
    }

    /** ACK 接入 HTTP handler（接到 LinkoraEndpointServlet，映射 /acks）。 */
    public LinkoraHttpHandler ackHttpHandler() {
        return new LinkoraHttpHandler() {
            @Override
            public LinkoraHttpResult handle(LinkoraHttpRequest request) {
                AckResult result = ackService.ack(toAuthRequest(request));
                if (result.isOk()) {
                    return jsonResult(200, codec.encodeAckResponse());
                }
                return jsonResult(result.httpStatus(),
                        codec.encodeErrorResponse(result.getErrorCode(), result.getErrorMessage()));
            }
        };
    }

    /** 手动触发一次补发扫描（测试与运维用）。 */
    public void sweepOnce() {
        coordinator.sweep();
    }

    // ---------------- 内部 ----------------

    private void ensureRunning() {
        if (closed.get()) {
            throw new LinkoraException(LinkoraErrorCode.INTERNAL_ERROR, "服务端已关闭");
        }
    }

    private void requireRegisteredClient(String clientId) {
        com.linkora.core.storage.server.ClientRegistration registration =
                clientRegistry.findByClientId(clientId).orElse(null);
        if (registration == null) {
            throw new LinkoraException(LinkoraErrorCode.FORBIDDEN,
                    "目标客户端未注册: " + clientId);
        }
        if (!registration.isEnabled()) {
            throw new LinkoraException(LinkoraErrorCode.FORBIDDEN,
                    "目标客户端已禁用: " + clientId);
        }
    }

    private static com.linkora.server.auth.AuthRequest toAuthRequest(LinkoraHttpRequest request) {
        return new com.linkora.server.auth.AuthRequest(
                request.getClientId(), request.getMethod(), request.getHeaders(), request.getBody());
    }

    private static LinkoraHttpResult jsonResult(int status, String json) {
        try {
            return new LinkoraHttpResult(status, json.getBytes(StandardCharsets.UTF_8),
                    "application/json;charset=UTF-8");
        } catch (RuntimeException e) {
            throw new LinkoraException(LinkoraErrorCode.INTERNAL_ERROR, "编码响应失败", e);
        }
    }

    public static final class Builder {
        private ClientAuthenticator authenticator;
        private ClientRegistry clientRegistry;
        private MessageRepository messageRepository;
        private DeliveryRepository deliveryRepository;
        private ClientCursorRepository cursorRepository;
        private PayloadSerializer payloadSerializer;
        private MessageJsonCodec codec = new MessageJsonCodec();
        private ServerConfig config = ServerConfig.builder().build();
        private SsePushGateway gateway;
        private SseConnectionRegistry sseRegistry;
        private ScheduledExecutorService externalScheduler;
        private final List<IngestListener> ingestListeners = new ArrayList<IngestListener>();

        public Builder clientAuthenticator(ClientAuthenticator authenticator) {
            this.authenticator = authenticator;
            return this;
        }

        public Builder clientRegistry(ClientRegistry clientRegistry) {
            this.clientRegistry = clientRegistry;
            return this;
        }

        public Builder messageRepository(MessageRepository messageRepository) {
            this.messageRepository = messageRepository;
            return this;
        }

        public Builder deliveryRepository(DeliveryRepository deliveryRepository) {
            this.deliveryRepository = deliveryRepository;
            return this;
        }

        public Builder cursorRepository(ClientCursorRepository cursorRepository) {
            this.cursorRepository = cursorRepository;
            return this;
        }

        /** 可选：启用对象负载 publish 时必须提供。 */
        public Builder payloadSerializer(PayloadSerializer payloadSerializer) {
            this.payloadSerializer = payloadSerializer;
            return this;
        }

        /** 可选：线路编解码（默认 JSON 实现）。 */
        public Builder messageCodec(MessageJsonCodec codec) {
            if (codec != null) {
                this.codec = codec;
            }
            return this;
        }

        public Builder config(ServerConfig config) {
            if (config != null) {
                this.config = config;
            }
            return this;
        }

        /** 可选：自定义推送网关（测试注入）。默认使用内置 SseConnectionRegistry。 */
        public Builder pushGateway(SsePushGateway gateway) {
            this.gateway = gateway;
            return this;
        }

        /** 可选：显式提供 SSE 注册表（默认自动创建）。 */
        public Builder sseConnectionRegistry(SseConnectionRegistry registry) {
            this.sseRegistry = registry;
            return this;
        }

        /** 可选：注入外部调度器（单线程语义由调用方保证），close() 不会关闭它。 */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.externalScheduler = scheduler;
            return this;
        }

        /** 可选：上行消息业务侧监听器；可多次调用累加，按注册顺序回调。 */
        public Builder ingestListener(IngestListener listener) {
            if (listener != null) {
                this.ingestListeners.add(listener);
            }
            return this;
        }

        /** 可选：批量注册上行消息监听器（追加，按注册顺序回调）。 */
        public Builder ingestListeners(List<IngestListener> listeners) {
            if (listeners != null) {
                for (IngestListener listener : listeners) {
                    if (listener != null) {
                        this.ingestListeners.add(listener);
                    }
                }
            }
            return this;
        }

        public LinkoraServer build() {
            if (authenticator == null) {
                throw new IllegalArgumentException("clientAuthenticator 不能为 null");
            }
            if (clientRegistry == null) {
                throw new IllegalArgumentException("clientRegistry 不能为 null");
            }
            if (messageRepository == null) {
                throw new IllegalArgumentException("messageRepository 不能为 null");
            }
            if (deliveryRepository == null) {
                throw new IllegalArgumentException("deliveryRepository 不能为 null");
            }
            if (cursorRepository == null) {
                throw new IllegalArgumentException("cursorRepository 不能为 null");
            }
            return new LinkoraServer(this);
        }
    }
}
