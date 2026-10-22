package com.linkora.server.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 基于 Servlet 3.1 异步能力的 SSE 推送端点。
 *
 * <p>设计目标：嵌入宿主已有 HTTP 容器（Tomcat/Jetty/WildFly 等），不强制另起服务器。
 * 容器负责 HTTP 解析与本模块只负责 SSE 帧写出、心跳与连接生命周期。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>GET 订阅；clientId 取请求头 {@code X-Linkora-Client-Id}，缺省取查询参数
 *       {@code clientId}，两者皆无时回写 400；</li>
 *   <li>请求头 {@code Last-Event-ID} 透传给 handler 作为续传游标
 *       （仅为传输层游标，不等于业务处理游标）；</li>
 *   <li>handler 返回 false 时回写 401；</li>
 *   <li>接受订阅后发送 {@code retry: reconnectHintMs} 建议客户端重连间隔，
 *       并按 {@code heartbeatIntervalMs} 周期发送 {@code :hb} 注释保活（0 表示禁用）；
 *       心跳与业务帧一样只入队，不阻塞调度线程；</li>
 *   <li>每连接有界发送队列：队列满即断开该连接（靠重连补发恢复）；
 *       单次 flush 卡住超过阈值由看门狗关闭连接；两者都会触发断开回调并清理注册表。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class SsePushServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(SsePushServlet.class);

    /** 客户端身份请求头。 */
    public static final String CLIENT_ID_HEADER = "X-Linkora-Client-Id";
    /** SSE 续传游标请求头（标准字段）。 */
    public static final String LAST_EVENT_ID_HEADER = "Last-Event-ID";
    private static final String CLIENT_ID_QUERY_PARAM = "clientId";

    /** 每连接发送队列默认容量（帧）。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;
    /** 单次 flush 卡住的判定阈值（毫秒）。 */
    public static final long DEFAULT_WRITE_STALL_TIMEOUT_MS = 15_000L;
    /** 写线程池默认线程数：一个卡死连接占一个线程，池满时其余连接排队但不会被阻塞。 */
    public static final int DEFAULT_WRITER_THREADS = 16;

    private final SseSubscribeHandler handler;
    private final SseConnectionRegistry registry;
    private final long heartbeatIntervalMs;
    private final long reconnectHintMs;
    private final ScheduledExecutorService heartbeatScheduler;
    private final java.util.concurrent.ExecutorService writerPool;
    private final int queueCapacity;
    private final long writeStallTimeoutMs;

    public SsePushServlet(SseSubscribeHandler handler, SseConnectionRegistry registry,
                          long heartbeatIntervalMs, long reconnectHintMs) {
        this(handler, registry, heartbeatIntervalMs, reconnectHintMs,
                DEFAULT_QUEUE_CAPACITY, DEFAULT_WRITE_STALL_TIMEOUT_MS, DEFAULT_WRITER_THREADS);
    }

    /**
     * @param queueCapacity       每连接发送队列容量，满即断开该连接
     * @param writeStallTimeoutMs 单次 flush 卡住多久后判定连接不可用并关闭（0 表示关闭看门狗）
     * @param writerThreads       写线程池大小
     */
    public SsePushServlet(SseSubscribeHandler handler, SseConnectionRegistry registry,
                          long heartbeatIntervalMs, long reconnectHintMs,
                          int queueCapacity, long writeStallTimeoutMs, int writerThreads) {
        if (handler == null || registry == null) {
            throw new IllegalArgumentException("handler 与 registry 不能为 null");
        }
        if (heartbeatIntervalMs < 0 || reconnectHintMs < 0) {
            throw new IllegalArgumentException("心跳与重连提示间隔不得为负数");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity 必须为正数");
        }
        if (writerThreads < 1) {
            throw new IllegalArgumentException("writerThreads 必须为正数");
        }
        this.handler = handler;
        this.registry = registry;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.reconnectHintMs = reconnectHintMs;
        this.queueCapacity = queueCapacity;
        this.writeStallTimeoutMs = writeStallTimeoutMs;
        this.heartbeatScheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "linkora-sse-heartbeat-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        this.writerPool = Executors.newFixedThreadPool(writerThreads, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "linkora-sse-writer-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        scheduleStalledConnectionGuard();
    }

    public SsePushServlet(SseSubscribeHandler handler, SseConnectionRegistry registry) {
        this(handler, registry, 15_000L, 3_000L);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String clientId = extractClientId(request);
        if (clientId == null || clientId.trim().isEmpty()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "缺少 clientId（请求头 " + CLIENT_ID_HEADER + " 或查询参数 clientId）");
            return;
        }
        String lastEventId = request.getHeader(LAST_EVENT_ID_HEADER);

        response.setContentType("text/event-stream;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("X-Accel-Buffering", "no");
        response.setBufferSize(0);

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0L); // SSE 长连接不受容器异步超时约束

        SseClientConnection connection = new SseClientConnection(
                clientId, asyncContext, response, new DisconnectAdapter(),
                writerPool, queueCapacity);

        // 先注册再交给 handler（handler 会在订阅成功后提交重连补发）：
        // 保证补发首推时连接已在注册表内，否则首推落空需等一个扫描周期
        registry.register(connection);
        boolean accepted;
        try {
            accepted = handler.onSubscribe(clientId, lastEventId, snapshotHeaders(request), connection);
        } catch (RuntimeException e) {
            log.warn("SSE 订阅处理异常: clientId={}", clientId, e);
            accepted = false;
        }
        if (!accepted || !connection.isOpen()) {
            registry.unregister(connection);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            asyncContext.complete();
            return;
        }

        writeInitialFrames(connection);
        scheduleHeartbeat(connection);
        log.debug("SSE 订阅建立: clientId={}, lastEventId={}", clientId, lastEventId);
    }

    @Override
    public void destroy() {
        heartbeatScheduler.shutdownNow();
        registry.closeAll();
        writerPool.shutdownNow();
        log.info("SsePushServlet 已销毁，心跳调度器与写线程池关闭且全部连接断开");
        super.destroy();
    }

    /**
     * 写超时看门狗：flush 卡在 TCP 发送窗口上时连接实际已不可用，
     * 主动关闭让它重连补发，避免占着写线程不放。
     */
    private void scheduleStalledConnectionGuard() {
        if (writeStallTimeoutMs <= 0) {
            return;
        }
        long period = Math.max(1_000L, Math.min(writeStallTimeoutMs / 2, 10_000L));
        heartbeatScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    long now = System.currentTimeMillis();
                    for (SseClientConnection connection : registry.allConnections()) {
                        if (connection.isWriteStalled(now, writeStallTimeoutMs)) {
                            log.warn("SSE 写出阻塞超过 {} ms，关闭该连接: clientId={}",
                                    writeStallTimeoutMs, connection.getClientId());
                            connection.close();
                        }
                    }
                } catch (RuntimeException e) {
                    log.warn("SSE 僵死连接巡检失败（下轮继续）", e);
                }
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private String extractClientId(HttpServletRequest request) {
        String header = request.getHeader(CLIENT_ID_HEADER);
        if (header != null && !header.trim().isEmpty()) {
            return header.trim();
        }
        return request.getParameter(CLIENT_ID_QUERY_PARAM);
    }

    /** 请求头快照（每个头取第一个值，保留原始头名大小写），供上层认证使用。 */
    private static java.util.Map<String, String> snapshotHeaders(HttpServletRequest request) {
        java.util.Enumeration<String> names = request.getHeaderNames();
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<String, String>();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }

    private void writeInitialFrames(SseClientConnection connection) {
        if (reconnectHintMs > 0) {
            // 与后续业务帧走同一条队列，保证顺序（写出由写线程池完成，不阻塞容器线程）
            connection.sendRawFrame(SseClientConnection.SseFrameBuilder.retry(reconnectHintMs));
        }
    }

    private void scheduleHeartbeat(final SseClientConnection connection) {
        if (heartbeatIntervalMs <= 0) {
            return;
        }
        ScheduledFuture<?> task = heartbeatScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                // 心跳以事件型发送（okhttp-sse 客户端不透出注释行，事件型可驱动其空闲检测）；
                // 写出失败会在连接内部触发关闭与断开回调，此处无需额外处理
                connection.sendEvent(null, SseHeartbeat.EVENT_TYPE, "hb");
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        connection.setHeartbeatTask(task);
    }

    /** 心跳事件类型约定。 */
    public static final class SseHeartbeat {
        public static final String EVENT_TYPE = "hb";

        private SseHeartbeat() {
        }
    }

    private final class DisconnectAdapter implements SseClientConnection.DisconnectCallback {
        @Override
        public void onDisconnected(SseClientConnection connection, Throwable cause) {
            registry.unregister(connection);
            try {
                handler.onDisconnect(connection.getClientId(), connection, cause);
            } catch (RuntimeException e) {
                log.warn("SSE 断开回调异常: clientId={}", connection.getClientId(), e);
            }
            log.debug("SSE 连接断开: clientId={}, cause={}", connection.getClientId(),
                    cause == null ? "服务端主动关闭" : cause.toString());
        }
    }
}
