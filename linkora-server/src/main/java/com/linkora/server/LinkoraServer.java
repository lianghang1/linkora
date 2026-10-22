package com.linkora.server;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.serialize.PayloadSerializer;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.ClientCursorRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
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

    private final AtomicBoolean running = new AtomicBoolean(false);
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
                builder.ingestListeners);
        this.ackService = new AckService(authenticator, deliveryRepository, coordinator, config);
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
        log.info("Linkora 服务端已启动（扫描周期 {} ms）", config.getSweepIntervalMs());
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
     * 发布字节负载消息：持久化优先，返回"已接受"后异步尝试 SSE 推送。
     *
     * @param expiresAt 可为 null（不过期）
     */
    public PublishResult publish(String clientId, String messageType, byte[] payload,
                                 Long expiresAt, String traceId) {
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
                    .build();
        } catch (IllegalArgumentException e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, e.getMessage(), e);
        }
        return publish(message);
    }

    /** 发布对象负载消息（需要构建时提供 payloadSerializer）。 */
    public PublishResult publish(String clientId, String messageType, Object payload) {
        if (payloadSerializer == null) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "未配置 payloadSerializer，无法序列化对象负载");
        }
        return publish(clientId, messageType, payloadSerializer.serialize(payload),
                null, null);
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
                        seq, com.linkora.core.message.MessageState.ACCEPTED, 0, null, null, now, now);
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
                seq, com.linkora.core.message.MessageState.ACCEPTED, 0, null, null, now, now));
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

    // ---------------- 宿主接入 ----------------

    /** SSE 订阅处理器（接到 SsePushServlet）。 */
    /**
     * 单个上行请求体的最大字节数，即 {@link ServerConfig#getMessageSizeLimitBytes()}。
     *
     * <p>这是**唯一默认值来源**：Servlet 层不再自带默认值，注册时必须显式传入本值，
     * 避免"配置了 4MB 却被 Servlet 的 2MB 硬上限拦下"这类不一致。</p>
     */
    public long getMaxBodyBytes() {
        return config.getMessageSizeLimitBytes();
    }

    public SseSubscribeHandler sseSubscribeHandler() {
        return coordinator;
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
