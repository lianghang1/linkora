package com.linkora.client;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.AckType;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.protocol.Protocol;
import com.linkora.core.serialize.PayloadSerializer;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.serialize.jackson.MessageJsonCodec;
import com.linkora.transport.http.HttpTransport;
import com.linkora.transport.http.OkHttpTransport;
import com.linkora.transport.http.TransportException;
import com.linkora.transport.http.TransportRequest;
import com.linkora.transport.http.TransportResponse;
import com.linkora.transport.sse.SseConnectionState;
import com.linkora.transport.sse.SseListener;
import com.linkora.transport.sse.SseStreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Linkora 客户端编排入口。
 *
 * <p>可靠性契约：</p>
 * <ul>
 *   <li>上行：先写 SQLite（outbox）再后台 POST；超时/响应丢失沿用原 messageId 重试，
 *       服务端按 messageId 幂等去重；</li>
 *   <li>下行：收到的消息先落 SQLite（inbox 去重），提交成功后才发 RECEIVED ACK，
 *       再交给业务监听器；业务成功发 PROCESSED ACK，失败发 FAILED ACK 并按配置重试；</li>
 *   <li>重启恢复：卡在 SENDING 的 outbox 复位、未发出的 ACK 补发、未处理消息续跑、
 *       已接受消息按保留策略清理；</li>
 *   <li>线程安全：start()/close() 幂等；close 后不再发起新请求。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class LinkoraClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LinkoraClient.class);

    /** 服务端推送的业务消息事件类型（心跳为 "hb"）。 */
    private static final String SERVER_MESSAGE_EVENT_TYPE = "msg";

    private final String clientId;
    private final String eventsUrl;
    private final String ingestUrl;
    private final String ackUrl;
    private final ClientStore store;
    private final CredentialProvider credentialProvider;
    private final PayloadSerializer payloadSerializer;
    private final MessageJsonCodec codec;
    private final ClientConfig config;

    private final HttpTransport transport;
    private final boolean ownsTransport;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    /**
     * 容错提取 messageId 用。messageId 是线路 JSON 的第一个字段，
     * 所以即使后续字段损坏/被截断也能取到。
     */
    private static final java.util.regex.Pattern MESSAGE_ID_PATTERN =
            java.util.regex.Pattern.compile("\"messageId\"\\s*:\\s*\"([^\"]{1,128})\"");

    private final ExecutorService ioExecutor;
    private final ExecutorService workerExecutor;
    private final AtomicBoolean drainQueued = new AtomicBoolean(false);
    /** 已见最大下行投递序号（SSE 事件 id），用于跳号检测；-1 表示尚未收到任何消息。 */
    private final AtomicLong lastDownSeq = new AtomicLong(-1);

    private volatile SseStreamClient sseClient;
    private volatile ClientState state = ClientState.NEW;
    private ScheduledFuture<?> senderTask;
    private ScheduledFuture<?> recoveryTask;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MessageListener>> listeners =
            new ConcurrentHashMap<String, CopyOnWriteArrayList<MessageListener>>();

    private final CopyOnWriteArrayList<OutboxListener> outboxListeners =
            new CopyOnWriteArrayList<OutboxListener>();

    private LinkoraClient(Builder builder) {
        this.clientId = builder.clientId;
        String base = builder.serverUrl;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.eventsUrl = base + builder.config.getEventsPath();
        this.ingestUrl = base + builder.config.getIngestPath();
        this.ackUrl = base + builder.config.getAckPath();
        this.store = builder.store;
        this.credentialProvider = builder.credentialProvider;
        this.payloadSerializer = builder.payloadSerializer;
        this.codec = builder.codec;
        this.config = builder.config;
        if (builder.externalTransport != null) {
            this.transport = builder.externalTransport;
            this.ownsTransport = false;
        } else {
            this.transport = OkHttpTransport.builder()
                    .connectTimeoutMs(config.getConnectTimeoutMs())
                    .readTimeoutMs(config.getRequestTimeoutMs())
                    .build();
            this.ownsTransport = true;
        }
        if (builder.externalScheduler != null) {
            this.scheduler = builder.externalScheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "linkora-client-timer-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });
            this.ownsScheduler = true;
        }
        this.ioExecutor = Executors.newSingleThreadExecutor(namedDaemonFactory("linkora-client-io"));
        this.workerExecutor = Executors.newSingleThreadExecutor(namedDaemonFactory("linkora-client-worker"));
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---------------- 生命周期 ----------------

    /** 启动连接与后台调度（幂等；重复调用不创建重复连接或线程）。 */
    public synchronized void start() {
        if (state == ClientState.CLOSED) {
            throw new LinkoraException(LinkoraErrorCode.INTERNAL_ERROR, "客户端已关闭，不得重新启动");
        }
        if (state == ClientState.RUNNING) {
            return;
        }
        state = ClientState.RUNNING;
        sseClient = SseStreamClient.builder()
                .endpoint(eventsUrl)
                .headersProvider(new SseStreamClient.HeadersProvider() {
                    @Override
                    public Map<String, String> headersForConnection() {
                        Map<String, String> headers = baseHeaders();
                        headers.putAll(credentialProvider.authHeaders(
                                new CredentialProvider.RequestMaterial("GET", null)));
                        return headers;
                    }
                })
                .listener(internalSseListener)
                .idleTimeoutMs(config.getIdleTimeoutMs())
                .connectTimeoutMs(config.getConnectTimeoutMs())
                .build();
        senderTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                submitDrain();
            }
        }, 0L, config.getSenderIntervalMs(), TimeUnit.MILLISECONDS);
        recoveryTask = scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                ioExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        recover();
                    }
                });
            }
        }, config.getRecoveryIntervalMs(), config.getRecoveryIntervalMs(), TimeUnit.MILLISECONDS);
        sseClient.start();
        log.info("Linkora 客户端已启动: clientId={}, server={}", clientId, eventsUrl);
    }

    /** 优雅关闭：停止连接与调度，不再发起新请求。幂等。 */
    @Override
    public synchronized void close() {
        if (state == ClientState.CLOSED) {
            return;
        }
        state = ClientState.CLOSED;
        // 先取消调度任务再关执行器：外部调度器（ownsScheduler=false）不会被关闭，
        // 不取消会让定时任务继续投递触发 RejectedExecutionException 噪音
        ScheduledFuture<?> sender = senderTask;
        if (sender != null) {
            sender.cancel(false);
        }
        ScheduledFuture<?> recovery = recoveryTask;
        if (recovery != null) {
            recovery.cancel(false);
        }
        SseStreamClient sse = sseClient;
        if (sse != null) {
            sse.close();
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
        shutdownExecutor(ioExecutor, "io");
        shutdownExecutor(workerExecutor, "worker");
        if (ownsTransport) {
            transport.close();
        }
        log.info("Linkora 客户端已关闭: clientId={}", clientId);
    }

    public ClientState getState() {
        return state;
    }

    /** SSE 连接状态；未启动时返回 null。 */
    public SseConnectionState getSseState() {
        SseStreamClient sse = sseClient;
        return sse == null ? null : sse.getState();
    }

    // ---------------- 监听器 ----------------

    /** 按消息类型注册业务监听器。 */
    public void addListener(String messageType, MessageListener listener) {
        if (messageType == null || messageType.trim().isEmpty() || listener == null) {
            throw new IllegalArgumentException("messageType 与 listener 不能为空");
        }
        CopyOnWriteArrayList<MessageListener> list = listeners.get(messageType);
        if (list == null) {
            list = new CopyOnWriteArrayList<MessageListener>();
            CopyOnWriteArrayList<MessageListener> existing = listeners.putIfAbsent(messageType, list);
            if (existing != null) {
                list = existing;
            }
        }
        list.add(listener);
    }

    /** 取消监听器。 */
    public void removeListener(String messageType, MessageListener listener) {
        if (messageType == null || listener == null) {
            return;
        }
        CopyOnWriteArrayList<MessageListener> list = listeners.get(messageType);
        if (list != null) {
            list.remove(listener);
        }
    }

    /** 注册 outbox 状态变更监听器（可注册多个，按注册顺序回调）。 */
    public void addOutboxListener(OutboxListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener 不能为空");
        }
        outboxListeners.add(listener);
    }

    /** 取消 outbox 状态变更监听器。 */
    public void removeOutboxListener(OutboxListener listener) {
        if (listener != null) {
            outboxListeners.remove(listener);
        }
    }

    // ---------------- 发布（线下 → 线上） ----------------

    /** 发布对象负载（需要构建时提供 payloadSerializer）。返回 messageId。 */
    public String publish(String messageType, Object payload) {
        return publish(messageType, payload, null);
    }

    /** 发布对象负载 + 顺序键：同一 sequenceKey 的消息严格按发布顺序被服务端接收。 */
    public String publish(String messageType, Object payload, String sequenceKey) {
        if (payloadSerializer == null) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "未配置 payloadSerializer，无法序列化对象负载");
        }
        return publish(messageType, payloadSerializer.serialize(payload), sequenceKey);
    }

    /**
     * 发布字节负载：先写 SQLite（幂等入队），后台线程负责 POST 上报与重试。
     * 客户端未启动时也允许入队（start 后自动补传）。
     */
    public String publish(String messageType, byte[] payload) {
        return publish(messageType, payload, null);
    }

    /**
     * 发布字节负载 + 顺序键：同一 sequenceKey 的消息严格按发布顺序被服务端接收
     * （前一条未被服务端确认前，后一条不会发出）。key 为 null 时行为与无序发布一致。
     */
    public String publish(String messageType, byte[] payload, String sequenceKey) {
        if (state == ClientState.CLOSED) {
            throw new LinkoraException(LinkoraErrorCode.INTERNAL_ERROR, "客户端已关闭");
        }
        long now = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        OutboxEntry entry;
        try {
            entry = new OutboxEntry(messageId, messageType, payload,
                    OutboxStatus.PENDING, now, now, 0, null, null, null, sequenceKey);
        } catch (IllegalArgumentException e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, e.getMessage(), e);
        }
        if (!store.saveOutbox(entry)) {
            throw new LinkoraException(LinkoraErrorCode.DUPLICATE_MESSAGE,
                    "messageId 冲突（极小概率），请重试: " + messageId);
        }
        submitDrain();
        log.debug("消息已入队 outbox: messageId={}, type={}", messageId, messageType);
        return messageId;
    }

    // ---------------- SSE 内部监听 ----------------

    private final SseListener internalSseListener = new SseListener() {
        @Override
        public void onOpen() {
            log.info("SSE 连接已建立: clientId={}", clientId);
        }

        @Override
        public void onEvent(String id, String type, String data) {
            // 管理指令走独立事件类型（不落库、不混业务消息，仅在线实时生效）
            if (com.linkora.core.admin.ClientCommands.EVENT_TYPE.equals(type)) {
                final String payload = data;
                ioExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleControlCommand(payload);
                    }
                });
                return;
            }
            if (!SERVER_MESSAGE_EVENT_TYPE.equals(type)) {
                return; // 心跳与未知类型忽略（服务端业务事件固定为 "msg"）
            }
            trackDownSeq(id);
            final String payload = data;
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    handleIncoming(payload);
                }
            });
        }

        @Override
        public void onClosed() {
            log.info("SSE 事件流被服务端关闭，将自动重连: clientId={}", clientId);
        }

        @Override
        public void onFailure(Throwable error, Integer httpCode) {
            log.warn("SSE 连接失败（将自动重连）: clientId={}, httpCode={}",
                    clientId, httpCode, error);
        }
    };

    // ---------------- IO 任务：下行接收 ----------------

    // 跳号意味着区间内消息不会再来（已过期 EXPIRED 或被服务端清理），业务方需知晓可能缺数据；
    // 补发与新消息并发推送时可能短暂乱序导致一次性误报，持续跳号才需排查
    private void trackDownSeq(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        long seq;
        try {
            seq = Long.parseLong(id);
        } catch (NumberFormatException e) {
            return;
        }
        long last = lastDownSeq.get();
        if (last >= 0 && seq > last + 1) {
            log.warn("下行序号跳号: ({}, {})，区间内消息可能已过期或被清理而丢失", last, seq);
        }
        // 游标只前进不回退，乱序到达的补发消息不影响检测
        while (seq > last && !lastDownSeq.compareAndSet(last, seq)) {
            last = lastDownSeq.get();
        }
    }

    private void handleIncoming(String json) {
        if (state != ClientState.RUNNING) {
            return;
        }
        LinkoraMessage message;
        try {
            message = codec.decodeMessage(json);
        } catch (LinkoraException e) {
            // 解不开也必须给服务端一个明确答复：否则它会按 pacing（最长 60s 一次）
            // 无限重推。messageId 是 JSON 的第一个字段，坏消息通常仍能抠出来。
            String failedId = extractMessageIdLenient(json);
            if (failedId == null) {
                log.warn("下行消息解析失败且无法提取 messageId，只能依赖消息过期兜底: {}",
                        e.getMessage());
                return;
            }
            log.warn("下行消息解析失败，回 FAILED 让服务端计数: messageId={}, 原因={}",
                    failedId, e.getMessage());
            final String ackId = failedId;
            final String reason = e.getMessage();
            ioExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    sendAck(ackId, AckType.FAILED,
                            LinkoraErrorCode.INVALID_MESSAGE.getCode(),
                            "下行解码失败: " + reason);
                }
            });
            return;
        }
        long now = System.currentTimeMillis();
        boolean isNew = store.saveInbox(new InboxEntry(message.getMessageId(), message.getMessageType(),
                message.getPayload(), ReceiveStatus.PERSISTED, ProcessStatus.PENDING,
                now, null, 0, null, null, message.getSequenceKey()));
        if (isNew) {
            // SQLite 已提交 → 立即发送 RECEIVED ACK（失败由恢复扫描补发）
            if (sendAck(message.getMessageId(), AckType.RECEIVED, null, null)) {
                markReceiveAckSent(message.getMessageId());
            }
            dispatchInbox(message.getMessageId());
            return;
        }
        // 重复投递：按本地状态补发丢失的 ACK 或重新武装重试
        InboxEntry existing = store.findInbox(message.getMessageId()).orElse(null);
        if (existing == null) {
            return;
        }
        if (existing.getReceiveStatus() == ReceiveStatus.PERSISTED) {
            if (sendAck(message.getMessageId(), AckType.RECEIVED, null, null)) {
                markReceiveAckSent(message.getMessageId());
            }
        }
        ProcessStatus processStatus = existing.getProcessStatus();
        if (processStatus == ProcessStatus.PROCESSED) {
            sendAck(message.getMessageId(), AckType.PROCESSED, null, null);
        } else if (processStatus == ProcessStatus.FAILED) {
            // 服务端重投到达：重新武装本地重试（服务端驱动续试）
            InboxEntry reArmed = existing.with(existing.getReceiveStatus(), ProcessStatus.RETRYING,
                    existing.getRetryCount(), now, existing.getLastError(), null);
            if (store.updateInboxProcessStatus(reArmed, ProcessStatus.FAILED)) {
                dispatchInbox(message.getMessageId());
            }
        }
        // PENDING / RETRYING / PROCESSING：由处理循环接管
    }

    private void markReceiveAckSent(String messageId) {
        InboxEntry entry = store.findInbox(messageId).orElse(null);
        if (entry == null) {
            return;
        }
        InboxEntry updated = entry.with(ReceiveStatus.RECEIVED_ACK_SENT, entry.getProcessStatus(),
                entry.getRetryCount(), entry.getNextRetryAt(), entry.getLastError(),
                entry.getProcessedAt());
        store.updateInboxReceiveStatus(updated, ReceiveStatus.PERSISTED);
    }

    /** 提交到 worker 线程按序处理。 */
    private void dispatchInbox(final String messageId) {
        workerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                processInbox(messageId);
            }
        });
    }

    /**
     * 从原始 JSON 文本里容错提取 messageId。
     *
     * <p>用于"解码失败但仍要回 FAILED"的场景：{@code messageId} 是编码时的第一个字段，
     * 所以 payload 损坏、JSON 被截断时通常仍能取到。取不到返回 null（只能靠过期兜底）。</p>
     *
     * <p>只做字面匹配，不做完整解析——此时 JSON 本身已经不合法，交给解析器只会再抛一次。</p>
     */
    static String extractMessageIdLenient(String json) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher matcher = MESSAGE_ID_PATTERN.matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private void processInbox(String messageId) {
        if (state != ClientState.RUNNING) {
            return;
        }
        InboxEntry entry = store.findInbox(messageId).orElse(null);
        if (entry == null) {
            return;
        }
        ProcessStatus status = entry.getProcessStatus();
        if (status != ProcessStatus.PENDING && status != ProcessStatus.RETRYING) {
            return;
        }
        if (status == ProcessStatus.RETRYING
                && entry.getNextRetryAt() != null
                && entry.getNextRetryAt() > System.currentTimeMillis()) {
            return;
        }
        CopyOnWriteArrayList<MessageListener> typeListeners = listeners.get(entry.getMessageType());
        if (typeListeners == null || typeListeners.isEmpty()) {
            // 无监听器：视为处理完成，避免无限重试
            markProcessed(messageId);
            return;
        }
        LinkoraMessage message = toMessage(entry);
        LinkoraReceivedMessage received = new LinkoraReceivedMessage(message, new LinkoraReceivedMessage.AckSink() {
            @Override
            public void onProcessed(String mid) {
                markProcessed(mid);
            }

            @Override
            public void onFailed(String mid, LinkoraErrorCode errorCode, String errorMessage) {
                markFailed(mid, errorCode, errorMessage);
            }
        });
        try {
            for (MessageListener listener : typeListeners) {
                listener.onMessage(received);
            }
            // 所有监听器正常返回且未显式 ACK：自动标记处理成功；
            // 若监听器已显式 ackProcessed/ackFailed，此调用幂等无效
            received.ackProcessed();
        } catch (RuntimeException e) {
            log.warn("业务监听器处理异常: messageId={}", messageId, e);
            received.ackFailed(LinkoraErrorCode.INTERNAL_ERROR,
                    "监听器异常: " + e.getMessage());
        }
    }

    private void markProcessed(String messageId) {
        InboxEntry entry = store.findInbox(messageId).orElse(null);
        if (entry == null || entry.getProcessStatus() == ProcessStatus.PROCESSED) {
            return;
        }
        InboxEntry updated = entry.with(entry.getReceiveStatus(), ProcessStatus.PROCESSED,
                entry.getRetryCount(), null, null, System.currentTimeMillis());
        if (store.updateInboxProcessStatus(updated, entry.getProcessStatus())) {
            sendAck(messageId, AckType.PROCESSED, null, null);
        } else {
            log.debug("PROCESSED CAS 竞争失败（并发处理或已推进）: messageId={}", messageId);
        }
    }

    private void markFailed(String messageId, LinkoraErrorCode errorCode, String errorMessage) {
        InboxEntry entry = store.findInbox(messageId).orElse(null);
        if (entry == null || entry.getProcessStatus() == ProcessStatus.PROCESSED) {
            return;
        }
        int nextRetry = entry.getRetryCount() + 1;
        String error = errorCode.getCode()
                + (errorMessage == null ? "" : ": " + errorMessage);
        boolean terminal = nextRetry >= config.getProcessMaxRetries();
        long now = System.currentTimeMillis();
        InboxEntry updated = entry.with(entry.getReceiveStatus(),
                terminal ? ProcessStatus.FAILED : ProcessStatus.RETRYING,
                nextRetry,
                terminal ? null : now + config.backoffDelayMs(nextRetry),
                error, now);
        if (store.updateInboxProcessStatus(updated, entry.getProcessStatus())) {
            sendAck(messageId, AckType.FAILED, errorCode.getCode(), errorMessage);
            if (terminal) {
                log.warn("业务处理失败次数耗尽，等待服务端重投驱动续试: messageId={}", messageId);
            }
        }
    }

    // ---------------- 管理接口：本地队列查询与上行死信处置 ----------------

    /**
     * 上行队列明细（管理视图，人类可读）。
     *
     * @param status 目标状态，null 表示全部状态
     * @param limit  上限（1~200，越界自动收敛）
     */
    public List<com.linkora.client.admin.OutboxView> listOutbox(OutboxStatus status, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<OutboxEntry> entries = new ArrayList<>();
        if (status != null) {
            entries.addAll(store.findOutbox(status, Long.MAX_VALUE, safeLimit));
        } else {
            for (OutboxStatus s : OutboxStatus.values()) {
                entries.addAll(store.findOutbox(s, Long.MAX_VALUE, safeLimit));
            }
            entries.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        }
        List<com.linkora.client.admin.OutboxView> result = new ArrayList<>();
        for (OutboxEntry entry : entries) {
            result.add(new com.linkora.client.admin.OutboxView(entry));
        }
        return result;
    }

    /** 上行死信列表（{@code listOutbox(DEAD_LETTER, limit)} 的便捷形式）。 */
    public List<com.linkora.client.admin.OutboxView> listOutboxDeadLetters(int limit) {
        return listOutbox(OutboxStatus.DEAD_LETTER, limit);
    }

    /**
     * 下行收件明细（管理视图，人类可读）。
     *
     * @param processStatus 处理状态，null 表示全部状态
     * @param limit         上限（1~200，越界自动收敛）
     */
    public List<com.linkora.client.admin.InboxView> listInbox(ProcessStatus processStatus,
                                                              int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<InboxEntry> entries = new ArrayList<>();
        if (processStatus != null) {
            entries.addAll(store.findInboxByProcessStatus(processStatus, Long.MAX_VALUE, safeLimit));
        } else {
            for (ProcessStatus s : ProcessStatus.values()) {
                entries.addAll(store.findInboxByProcessStatus(s, Long.MAX_VALUE, safeLimit));
            }
            entries.sort((a, b) -> Long.compare(b.getReceivedAt(), a.getReceivedAt()));
        }
        List<com.linkora.client.admin.InboxView> result = new ArrayList<>();
        for (InboxEntry entry : entries) {
            result.add(new com.linkora.client.admin.InboxView(entry));
        }
        return result;
    }

    /**
     * 重发一条上行死信：DEAD_LETTER → PENDING（重试计数清零），并立即触发发送。
     *
     * @return true 表示已复活进入发送队列；false 表示记录不存在或不是死信
     */
    public boolean retryOutboxDeadLetter(String messageId) {
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        OutboxEntry entry = store.findOutbox(messageId).orElse(null);
        if (entry == null || entry.getStatus() != OutboxStatus.DEAD_LETTER) {
            return false;
        }
        OutboxEntry reset = entry.with(OutboxStatus.PENDING, 0, null,
                "人工重发（管理指令）", System.currentTimeMillis());
        if (!transitionOutbox(reset, OutboxStatus.DEAD_LETTER)) {
            return false;
        }
        log.info("管理操作重发上行死信: messageId={}", messageId);
        submitDrain();
        return true;
    }

    /**
     * 放弃一条上行死信（保持死信状态，lastError 留痕区分"已人工放弃"）。
     *
     * @return true 表示标记成功；false 表示记录不存在或不是死信
     */
    public boolean discardOutboxDeadLetter(String messageId, String reason) {
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        OutboxEntry entry = store.findOutbox(messageId).orElse(null);
        if (entry == null || entry.getStatus() != OutboxStatus.DEAD_LETTER) {
            return false;
        }
        OutboxEntry marked = entry.with(OutboxStatus.DEAD_LETTER, entry.getRetryCount(),
                null, "人工放弃: " + reason, System.currentTimeMillis());
        boolean ok = transitionOutbox(marked, OutboxStatus.DEAD_LETTER);
        if (ok) {
            log.info("管理操作放弃上行死信: messageId={}, 原因: {}", messageId, reason);
        }
        return ok;
    }

    // ---------------- 管理指令（服务端 ctrl 事件下发，白名单分发） ----------------

    /**
     * 处理服务端下发的管理指令。任何异常都就地消化并告警——
     * 指令通道的故障绝不能影响业务消息处理。
     */
    private void handleControlCommand(String json) {
        if (state != ClientState.RUNNING || json == null || json.isEmpty()) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = com.linkora.serialize.jackson.LinkoraJsonUtil.readTree(json);
            String command = node.path("cmd").asText("");
            if (!com.linkora.core.admin.ClientCommands.isKnown(command)) {
                log.warn("收到未知管理指令，忽略: {}", command);
                return;
            }
            com.fasterxml.jackson.databind.JsonNode params = node.path("params");
            switch (command) {
                case com.linkora.core.admin.ClientCommands.PING:
                    log.info("管理指令 PING：指令通道连通（clientId={}）", clientId);
                    break;
                case com.linkora.core.admin.ClientCommands.RETRY_OUTBOX_DEAD_LETTER: {
                    String messageId = params.path("messageId").asText(null);
                    boolean ok = retryOutboxDeadLetter(messageId);
                    log.info("管理指令重发上行死信: messageId={}, 结果={}", messageId, ok);
                    break;
                }
                case com.linkora.core.admin.ClientCommands.DISCARD_OUTBOX_DEAD_LETTER: {
                    String messageId = params.path("messageId").asText(null);
                    String reason = params.path("reason").asText("");
                    boolean ok = discardOutboxDeadLetter(messageId, reason);
                    log.info("管理指令放弃上行死信: messageId={}, 结果={}", messageId, ok);
                    break;
                }
                case com.linkora.core.admin.ClientCommands.QUERY_OUTBOX: {
                    String queryId = params.path("queryId").asText(null);
                    String statusName = params.path("status").asText(null);
                    int limit = Math.max(1, Math.min(50, params.path("limit").asInt(50)));
                    OutboxStatus status = statusName == null ? null : OutboxStatus.valueOf(statusName);
                    List<com.linkora.client.admin.OutboxView> rows = listOutbox(status, limit);
                    StringBuilder rowsJson = new StringBuilder("[");
                    for (int i = 0; i < rows.size(); i++) {
                        com.linkora.client.admin.OutboxView row = rows.get(i);
                        if (i > 0) {
                            rowsJson.append(',');
                        }
                        rowsJson.append(com.linkora.serialize.jackson.LinkoraJsonUtil.toJson(row));
                    }
                    rowsJson.append(']');
                    String resultPayload = "{\"queryId\":\"" + queryId + "\",\"clientId\":\""
                            + clientId + "\",\"rows\":" + rowsJson + "}";
                    publish(com.linkora.core.admin.ClientCommands.RESULT_MESSAGE_TYPE,
                            resultPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    log.info("管理指令远程查询完成: queryId={}, 返回 {} 行", queryId, rows.size());
                    break;
                }
                default:
                    log.warn("收到未实现的管理指令，忽略: {}", command);
            }
        } catch (RuntimeException e) {
            log.warn("管理指令处理失败（不影响业务消息）: {}", e.getMessage());
        }
    }

    // ---------------- IO 任务：上行发送 ----------------

    /** 合并去重的补传触发。 */
    private void submitDrain() {
        if (drainQueued.compareAndSet(false, true)) {
            try {
                ioExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        drainQueued.set(false);
                        drainOutbox();
                    }
                });
            } catch (RejectedExecutionException e) {
                // 与 close() 竞态：执行器已关闭。消息已在 outbox 落库，
                // 重启后由恢复扫描补传，此处静默复位去重标记
                drainQueued.set(false);
            }
        }
    }

    private void drainOutbox() {
        long now = System.currentTimeMillis();
        List<OutboxEntry> pending = store.findOutbox(OutboxStatus.PENDING, Long.MAX_VALUE,
                config.getOutboxBatchSize());
        List<OutboxEntry> failed = store.findOutbox(OutboxStatus.FAILED, now,
                config.getOutboxBatchSize());
        if (pending.isEmpty() && failed.isEmpty()) {
            return;
        }
        for (OutboxEntry entry : pending) {
            if (state != ClientState.RUNNING) {
                return;
            }
            claimAndSend(entry, now);
        }
        for (OutboxEntry entry : failed) {
            if (state != ClientState.RUNNING) {
                return;
            }
            claimAndSend(entry, now);
        }
    }

    private void claimAndSend(OutboxEntry entry, long now) {
        if (entry.getExpiresAt() != null && entry.getExpiresAt() < now) {
            transitionOutbox(entry.with(OutboxStatus.DEAD_LETTER, entry.getRetryCount(),
                    null, "消息已过期", now), entry.getStatus());
            return;
        }
        // 上行顺序门禁：同键更早的消息还没终结（未确认/未死信）时让路，
        // 防止"重试中的旧消息"被同键新消息超车先发出去；留到下轮发送
        if (entry.getSequenceKey() != null
                && store.hasEarlierActiveInKey(entry.getSequenceKey(), entry.getMessageId())) {
            return;
        }
        OutboxEntry claimed = entry.with(OutboxStatus.SENDING, entry.getRetryCount(),
                null, null, System.currentTimeMillis());
        if (!transitionOutbox(claimed, entry.getStatus())) {
            return; // 竞争失败（并发发送或状态已推进）
        }
        sendOne(claimed);
    }

    private void sendOne(OutboxEntry entry) {
        LinkoraMessage message;
        try {
            message = LinkoraMessage.builder()
                    .messageId(entry.getMessageId())
                    .clientId(clientId)
                    .messageType(entry.getMessageType())
                    .payload(entry.getPayload())
                    .createdAt(entry.getCreatedAt())
                    .expiresAt(entry.getExpiresAt())
                    .schemaVersion(Protocol.PROTOCOL_VERSION)
                    .sequenceKey(entry.getSequenceKey())
                    .build();
        } catch (IllegalArgumentException e) {
            transitionOutbox(entry.with(OutboxStatus.DEAD_LETTER, entry.getRetryCount(),
                    null, "本地消息非法: " + e.getMessage(), System.currentTimeMillis()),
                    OutboxStatus.SENDING);
            return;
        }
        byte[] body = codec.encodeMessage(message).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = baseHeaders();
        headers.put("Content-Type", "application/json");
        headers.putAll(credentialProvider.authHeaders(
                new CredentialProvider.RequestMaterial("POST", body)));
        TransportRequest request = TransportRequest.newBuilder(ingestUrl)
                .method("POST")
                .headers(headers)
                .body(body)
                .connectTimeoutMs(config.getConnectTimeoutMs())
                .readTimeoutMs(config.getRequestTimeoutMs())
                .build();
        try {
            TransportResponse response = transport.execute(request);
            if (response.isSuccessful()) {
                boolean accepted;
                try {
                    accepted = codec.isIngestAccepted(response.getBody());
                } catch (LinkoraException e) {
                    accepted = false;
                }
                if (accepted) {
                    transitionOutbox(entry.with(OutboxStatus.SERVER_ACCEPTED,
                            entry.getRetryCount(), null, null, System.currentTimeMillis()),
                            OutboxStatus.SENDING);
                } else {
                    handleSendFailure(entry, true, "服务端响应格式异常（将重试）");
                }
            } else {
                int code = response.getCode();
                boolean retryable = code == 408 || code == 429 || code >= 500;
                String detail = "HTTP " + code + " " + truncateBody(response.getBody());
                handleSendFailure(entry, retryable, detail);
            }
        } catch (TransportException e) {
            handleSendFailure(entry, e.isRetryable(), e.getMessage());
        }
    }

    private void handleSendFailure(OutboxEntry entry, boolean retryable, String error) {
        int nextRetry = entry.getRetryCount() + 1;
        long now = System.currentTimeMillis();
        boolean dead = !retryable || nextRetry >= config.getOutboxMaxRetries();
        OutboxEntry updated;
        if (dead) {
            updated = entry.with(OutboxStatus.DEAD_LETTER, nextRetry, null,
                    "消息进入死信: " + error, now);
            log.warn("上行消息进入死信: messageId={}, 原因: {}", entry.getMessageId(), error);
        } else {
            long delay = config.backoffDelayMs(nextRetry);
            updated = entry.with(OutboxStatus.FAILED, nextRetry, now + delay, error, now);
            log.warn("上行发送失败，#{} 次重试将于 {} ms 后: messageId={}, 原因: {}",
                    nextRetry, delay, entry.getMessageId(), error);
        }
        transitionOutbox(updated, OutboxStatus.SENDING);
    }

    // ---------------- IO 任务：恢复扫描 ----------------

    private void recover() {
        if (state != ClientState.RUNNING) {
            return;
        }
        try {
            // 1. 崩溃残留：SENDING → PENDING
            for (OutboxEntry stuck : store.findOutbox(OutboxStatus.SENDING, Long.MAX_VALUE,
                    config.getOutboxBatchSize())) {
                transitionOutbox(stuck.with(OutboxStatus.PENDING, stuck.getRetryCount(),
                        null, stuck.getLastError(), System.currentTimeMillis()), OutboxStatus.SENDING);
            }
            // 2. 补发未完成的 RECEIVED ACK
            for (InboxEntry entry : store.findInboxPendingReceiveAck(config.getInboxBatchSize())) {
                if (sendAck(entry.getMessageId(), AckType.RECEIVED, null, null)) {
                    markReceiveAckSent(entry.getMessageId());
                }
            }
            // 3. 续跑未处理 / 到期重试的消息
            for (InboxEntry entry : store.findInboxByProcessStatus(ProcessStatus.PENDING,
                    Long.MAX_VALUE, config.getInboxBatchSize())) {
                dispatchInbox(entry.getMessageId());
            }
            for (InboxEntry entry : store.findInboxByProcessStatus(ProcessStatus.RETRYING,
                    System.currentTimeMillis(), config.getInboxBatchSize())) {
                dispatchInbox(entry.getMessageId());
            }
            // 4. 补传上行
            submitDrain();
            // 5. 保留策略清理
            long now = System.currentTimeMillis();
            store.deleteOutboxAcceptedBefore(now - config.getOutboxRetentionMs());
            store.deleteInboxProcessedBefore(now - config.getInboxRetentionMs());
        } catch (RuntimeException e) {
            log.warn("恢复扫描失败（下轮继续）", e);
        }
    }

    // ---------------- ACK 发送 ----------------

    /** 同步发送 ACK；成功返回 true。失败仅记录，由恢复扫描或重复投递补发（幂等接口）。 */
    private boolean sendAck(String messageId, AckType ackType, String errorCode, String errorMessage) {
        byte[] body = codec.encodeAck(messageId, ackType, errorCode, errorMessage)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, String> headers = baseHeaders();
        headers.put("Content-Type", "application/json");
        headers.putAll(credentialProvider.authHeaders(
                new CredentialProvider.RequestMaterial("POST", body)));
        TransportRequest request = TransportRequest.newBuilder(ackUrl)
                .method("POST")
                .headers(headers)
                .body(body)
                .connectTimeoutMs(config.getConnectTimeoutMs())
                .readTimeoutMs(config.getRequestTimeoutMs())
                .build();
        try {
            TransportResponse response = transport.execute(request);
            if (!response.isSuccessful()) {
                log.warn("ACK 发送被拒绝（将按幂等补发）: messageId={}, ackType={}, http={}",
                        messageId, ackType, response.getCode());
                return false;
            }
            boolean ok;
            try {
                ok = codec.isAckResponseOk(response.getBody());
            } catch (LinkoraException e) {
                ok = false;
            }
            if (!ok) {
                log.warn("ACK 响应异常（将按幂等补发）: messageId={}, ackType={}", messageId, ackType);
            }
            return ok;
        } catch (TransportException e) {
            log.warn("ACK 发送失败（将按幂等补发）: messageId={}, ackType={}, 原因: {}",
                    messageId, ackType, e.getMessage());
            return false;
        }
    }

    // ---------------- 工具 ----------------

    /** CAS 推进 outbox 状态；成功后按注册顺序通知监听器。 */
    private boolean transitionOutbox(OutboxEntry updated, OutboxStatus previousStatus) {
        if (!store.updateOutboxStatus(updated, previousStatus)) {
            return false;
        }
        for (OutboxListener listener : outboxListeners) {
            try {
                listener.onStatusChanged(updated, previousStatus);
            } catch (RuntimeException e) {
                log.warn("OutboxListener 回调异常（不影响消息状态）: messageId={}",
                        updated.getMessageId(), e);
            }
        }
        return true;
    }

    private LinkoraMessage toMessage(InboxEntry entry) {
        return LinkoraMessage.builder()
                .messageId(entry.getMessageId())
                .clientId(clientId)
                .messageType(entry.getMessageType())
                .payload(entry.getPayload())
                .createdAt(entry.getReceivedAt())
                .schemaVersion(Protocol.PROTOCOL_VERSION)
                .sequenceKey(entry.getSequenceKey())
                .build();
    }

    private Map<String, String> baseHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Linkora-Client-Id", clientId);
        headers.put(Protocol.PROTOCOL_VERSION_HEADER, String.valueOf(Protocol.PROTOCOL_VERSION));
        return headers;
    }

    private static String truncateBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static ThreadFactory namedDaemonFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }

    private static void shutdownExecutor(ExecutorService executor, String name) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("客户端 {} 线程池未在期限内终止", name);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static final class Builder {
        private String serverUrl;
        private String clientId;
        private ClientStore store;
        private CredentialProvider credentialProvider = new NoCredentialProvider();
        private PayloadSerializer payloadSerializer;
        private MessageJsonCodec codec = new MessageJsonCodec();
        private ClientConfig config = ClientConfig.builder().build();
        private HttpTransport externalTransport;
        private ScheduledExecutorService externalScheduler;

        public Builder serverUrl(String serverUrl) {
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("serverUrl 不能为空");
            }
            this.serverUrl = serverUrl.trim();
            return this;
        }

        public Builder clientId(String clientId) {
            if (clientId == null || clientId.trim().isEmpty()) {
                throw new IllegalArgumentException("clientId 不能为空");
            }
            this.clientId = clientId.trim();
            return this;
        }

        /** 必填：本地持久化存储（生产使用内置 SqliteClientStore）。 */
        public Builder clientStore(ClientStore store) {
            this.store = store;
            return this;
        }

        /** 可选：认证凭据提供者（默认匿名）。 */
        public Builder credentialProvider(CredentialProvider credentialProvider) {
            if (credentialProvider != null) {
                this.credentialProvider = credentialProvider;
            }
            return this;
        }

        /** 可选：对象负载序列化器（默认 JSON 时提供 JacksonPayloadSerializer）。 */
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

        public Builder config(ClientConfig config) {
            if (config != null) {
                this.config = config;
            }
            return this;
        }

        /** 可选：注入外部 HTTP 传输（close 时不关闭它）。 */
        public Builder transport(HttpTransport transport) {
            this.externalTransport = transport;
            return this;
        }

        /** 可选：注入外部调度器（单线程语义由调用方保证），close() 不会关闭它。 */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.externalScheduler = scheduler;
            return this;
        }

        public LinkoraClient build() {
            if (serverUrl == null) {
                throw new IllegalArgumentException("serverUrl 不能为空");
            }
            if (clientId == null) {
                throw new IllegalArgumentException("clientId 不能为空");
            }
            if (store == null) {
                throw new IllegalArgumentException("clientStore 不能为空");
            }
            return new LinkoraClient(this);
        }
    }
}
