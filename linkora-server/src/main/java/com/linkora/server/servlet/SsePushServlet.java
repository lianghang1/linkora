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
 *       并按 {@code heartbeatIntervalMs} 周期发送 {@code :hb} 注释保活（0 表示禁用）；</li>
 *   <li>写出失败或容器异步超时会触发断开回调并清理注册表。</li>
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

    private final SseSubscribeHandler handler;
    private final SseConnectionRegistry registry;
    private final long heartbeatIntervalMs;
    private final long reconnectHintMs;
    private final ScheduledExecutorService heartbeatScheduler;

    public SsePushServlet(SseSubscribeHandler handler, SseConnectionRegistry registry,
                          long heartbeatIntervalMs, long reconnectHintMs) {
        if (handler == null || registry == null) {
            throw new IllegalArgumentException("handler 与 registry 不能为 null");
        }
        if (heartbeatIntervalMs < 0 || reconnectHintMs < 0) {
            throw new IllegalArgumentException("心跳与重连提示间隔不得为负数");
        }
        this.handler = handler;
        this.registry = registry;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.reconnectHintMs = reconnectHintMs;
        this.heartbeatScheduler = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "linkora-sse-heartbeat-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
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
                clientId, asyncContext, response, new DisconnectAdapter());

        boolean accepted;
        try {
            accepted = handler.onSubscribe(clientId, lastEventId, snapshotHeaders(request), connection);
        } catch (RuntimeException e) {
            log.warn("SSE 订阅处理异常: clientId={}", clientId, e);
            accepted = false;
        }
        if (!accepted || !connection.isOpen()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            asyncContext.complete();
            return;
        }

        registry.register(connection);
        writeInitialFrames(connection);
        scheduleHeartbeat(connection);
        log.debug("SSE 订阅建立: clientId={}, lastEventId={}", clientId, lastEventId);
    }

    @Override
    public void destroy() {
        heartbeatScheduler.shutdownNow();
        registry.closeAll();
        log.info("SsePushServlet 已销毁，心跳调度器关闭且全部连接断开");
        super.destroy();
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
            connection.write(SseClientConnection.SseFrameBuilder.retry(reconnectHintMs));
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
