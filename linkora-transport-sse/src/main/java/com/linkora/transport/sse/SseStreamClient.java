package com.linkora.transport.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * 自动重连的 SSE 下行客户端（基于 okhttp-sse 成熟解析器，不手写事件流解析）。
 *
 * <p>能力：</p>
 * <ul>
 *   <li>断线自动重连（指数退避，策略可替换），重连时自动携带
 *       {@code Last-Event-ID} 请求头实现服务端续传；</li>
 *   <li>空闲检测：超过 {@code idleTimeoutMs} 未收到任何事件
 *       视为死链，主动断开并触发重连（0 表示禁用）；</li>
 *   <li>每条连接的请求头由 {@link HeadersProvider} 提供，支持重连时轮换鉴权凭据；</li>
 *   <li>手动 {@link #close()} 后不再重连；{@link #start()} 幂等。</li>
 * </ul>
 *
 * <p>注意：{@code Last-Event-ID} 只是传输层续传游标，不等于业务处理游标；
 * 业务 ACK 状态由上层存储与调度负责。</p>
 */
public final class SseStreamClient {

    private static final Logger log = LoggerFactory.getLogger(SseStreamClient.class);

    /** 服务端心跳事件类型（SseListener 约定）。 */
    public static final String HEARTBEAT_EVENT_TYPE = "hb";

    /** SSE 续传游标请求头（标准字段）。 */
    public static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";

    private final String endpoint;
    private final HeadersProvider headersProvider;
    private final SseListener userListener;
    private final ReconnectPolicy reconnectPolicy;
    private final long idleTimeoutMs;
    private final OkHttpClient client;
    private final boolean ownsClient;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<EventSource> currentSource = new AtomicReference<EventSource>();
    private final AtomicReference<ScheduledFuture<?>> idleWatchdog =
            new AtomicReference<ScheduledFuture<?>>();

    private volatile SseConnectionState state = SseConnectionState.CLOSED;
    private volatile String lastEventId;
    private volatile long lastActivityMillis;

    private SseStreamClient(Builder builder) {
        this.endpoint = builder.endpoint;
        this.headersProvider = builder.headersProvider;
        this.userListener = builder.listener;
        this.reconnectPolicy = builder.reconnectPolicy;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        if (builder.externalClient != null) {
            this.client = builder.externalClient;
            this.ownsClient = false;
        } else {
            this.client = new OkHttpClient.Builder()
                    .connectTimeout(builder.connectTimeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(10_000L, TimeUnit.MILLISECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接不设读超时，由空闲检测兜底
                    .retryOnConnectionFailure(true)
                    .build();
            this.ownsClient = true;
        }
        if (builder.externalScheduler != null) {
            this.scheduler = builder.externalScheduler;
            this.ownsScheduler = false;
        } else {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "linkora-sse-" + System.identityHashCode(this));
                t.setDaemon(true);
                return t;
            });
            this.ownsScheduler = true;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 启动连接与自动重连。重复调用不会创建重复连接。 */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);
        state = SseConnectionState.CONNECTING;
        log.info("SSE 客户端启动: {}", endpoint);
        connect();
        startIdleWatchdog();
    }

    /**
     * 手动关闭。取消当前连接与所有定时任务；已排队的回调会被忽略，不再重连。
     */
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        state = SseConnectionState.CLOSED;
        cancelIdleWatchdog();
        EventSource source = currentSource.getAndSet(null);
        if (source != null) {
            source.cancel();
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
        log.info("SSE 客户端已手动关闭: {}", endpoint);
    }

    public SseConnectionState getState() {
        return state;
    }

    /** 最近一次收到的服务端事件 id，可为 null。 */
    public String getLastEventId() {
        return lastEventId;
    }

    public boolean isRunning() {
        return running.get();
    }

    private void connect() {
        if (!running.get()) {
            return;
        }
        state = SseConnectionState.CONNECTING;
        Request.Builder requestBuilder = new Request.Builder().url(endpoint);
        Map<String, String> headers = headersProvider.headersForConnection();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.header(entry.getKey(), entry.getValue());
            }
        }
        String cursor = lastEventId;
        if (cursor != null && !cursor.isEmpty()) {
            requestBuilder.header(LAST_EVENT_ID_HEADER, cursor);
        }
        EventSources.createFactory(client).newEventSource(requestBuilder.build(), internalListener);
    }

    private final EventSourceListener internalListener = new EventSourceListener() {
        @Override
        public void onOpen(EventSource eventSource, Response response) {
            post(() -> handleOpen(eventSource));
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            post(() -> handleEvent(id, type, data));
        }

        @Override
        public void onClosed(EventSource eventSource) {
            post(() -> handleStreamEnd(true, null, null));
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            Integer httpCode = response == null ? null : response.code();
            String message = t == null ? "连接失败" : t.getMessage();
            post(() -> handleStreamEnd(false, t, httpCode));
        }
    };

    /** okhttp 回调线程 → 单线程调度器，串行化处理。 */
    private void post(Runnable task) {
        try {
            scheduler.execute(task);
        } catch (RejectedExecutionException ignored) {
            // 关闭后的迟到回调，忽略
        }
    }

    private void handleOpen(EventSource eventSource) {
        if (!running.get()) {
            return;
        }
        currentSource.set(eventSource);
        state = SseConnectionState.OPEN;
        consecutiveFailures.set(0);
        reconnectPending.set(false);
        lastActivityMillis = System.currentTimeMillis();
        log.debug("SSE 连接建立: {}", endpoint);
        notifyOpen();
    }

    private void handleEvent(String id, String type, String data) {
        if (!running.get()) {
            return;
        }
        lastActivityMillis = System.currentTimeMillis();
        if (id != null && !id.isEmpty()) {
            lastEventId = id;
        }
        notifyEvent(id, type, data);
    }

    /**
     * 流结束统一处理。
     *
     * @param closedNormally true 为服务端正常关闭事件流；false 为失败
     *                       （{@code error} 与 {@code httpCode} 均可能为 null，如纯 HTTP 错误响应）
     */
    private void handleStreamEnd(boolean closedNormally, Throwable error, Integer httpCode) {
        if (!running.get()) {
            return;
        }
        currentSource.set(null);
        state = SseConnectionState.CONNECTING;
        int failures = consecutiveFailures.incrementAndGet();
        if (closedNormally) {
            notifyClosed();
        } else {
            log.warn("SSE 连接断开（第 {} 次）: {} - {}",
                    failures, endpoint, httpCode == null ? "网络/未知错误" : "HTTP " + httpCode);
            notifyFailure(error, httpCode);
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (!reconnectPending.compareAndSet(false, true)) {
            return;
        }
        long delay = reconnectPolicy.nextDelayMs(consecutiveFailures.get(), null);
        log.info("将在 {} ms 后重连 SSE: {}", delay, endpoint);
        try {
            scheduler.schedule(this::reconnectNow, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            reconnectPending.set(false);
        }
    }

    private void reconnectNow() {
        reconnectPending.set(false);
        connect();
    }

    private void startIdleWatchdog() {
        if (idleTimeoutMs <= 0) {
            return;
        }
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }
            EventSource source = currentSource.get();
            if (source == null) {
                return; // 尚未建连（正在重连退避），无流可取消
            }
            long idle = System.currentTimeMillis() - lastActivityMillis;
            if (idle >= idleTimeoutMs) {
                log.warn("SSE 连接空闲超过 {} ms，判定为死链并主动重连: {}", idleTimeoutMs, endpoint);
                source.cancel(); // 触发 onFailure → handleStreamEnd → 重连
            }
        }, idleTimeoutMs, idleTimeoutMs, TimeUnit.MILLISECONDS);
        idleWatchdog.set(future);
    }

    private void cancelIdleWatchdog() {
        ScheduledFuture<?> future = idleWatchdog.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void notifyOpen() {
        try {
            userListener.onOpen();
        } catch (Exception e) {
            log.warn("用户 onOpen 回调异常", e);
        }
    }

    private void notifyEvent(String id, String type, String data) {
        try {
            userListener.onEvent(id, type, data);
        } catch (Exception e) {
            log.warn("用户 onEvent 回调异常", e);
        }
    }

    private void notifyClosed() {
        try {
            userListener.onClosed();
        } catch (Exception e) {
            log.warn("用户 onClosed 回调异常", e);
        }
    }

    private void notifyFailure(Throwable error, Integer httpCode) {
        try {
            userListener.onFailure(error, httpCode);
        } catch (Exception e) {
            log.warn("用户 onFailure 回调异常", e);
        }
    }

    /** 每次建连时提供请求头（支持轮换鉴权凭据）。实现必须线程安全。 */
    public interface HeadersProvider {
        Map<String, String> headersForConnection();
    }

    public static final class Builder {
        private String endpoint;
        private HeadersProvider headersProvider = new HeadersProvider() {
            @Override
            public Map<String, String> headersForConnection() {
                return new LinkedHashMap<String, String>();
            }
        };
        private SseListener listener;
        private ReconnectPolicy reconnectPolicy = ExponentialReconnectPolicy.defaultPolicy();
        private long idleTimeoutMs = 45_000L;
        private long connectTimeoutMs = 10_000L;
        private OkHttpClient externalClient;
        private ScheduledExecutorService externalScheduler;

        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder headersProvider(HeadersProvider headersProvider) {
            if (headersProvider != null) {
                this.headersProvider = headersProvider;
            }
            return this;
        }

        public Builder listener(SseListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("listener 不能为 null");
            }
            this.listener = listener;
            return this;
        }

        public Builder reconnectPolicy(ReconnectPolicy policy) {
            if (policy != null) {
                this.reconnectPolicy = policy;
            }
            return this;
        }

        /** 空闲超时（毫秒），0 表示禁用空闲检测。默认 45s。 */
        public Builder idleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        /** 注入外部 OkHttp 实例时，close() 不会关闭它。 */
        public Builder client(OkHttpClient client) {
            this.externalClient = client;
            return this;
        }

        /** 注入外部调度器时（单线程语义由调用方保证），close() 不会关闭它。 */
        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.externalScheduler = scheduler;
            return this;
        }

        public SseStreamClient build() {
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalArgumentException("endpoint 不能为空");
            }
            if (listener == null) {
                throw new IllegalArgumentException("listener 不能为 null");
            }
            return new SseStreamClient(this);
        }
    }
}
