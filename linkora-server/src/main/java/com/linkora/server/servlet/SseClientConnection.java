package com.linkora.server.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;

/**
 * 服务端到单个客户端的 SSE 连接封装。所有写出方法线程安全且同步刷出；
 * 写出失败会自动关闭连接并触发断开回调。
 *
 * @author lianghang
 */
public final class SseClientConnection {

    private static final Logger log = LoggerFactory.getLogger(SseClientConnection.class);

    private final String clientId;
    private final AsyncContext asyncContext;
    private final HttpServletResponse response;
    /** 断开时的清理回调（由 servlet 注入：注销注册表 + 通知 handler）。 */
    private final DisconnectCallback disconnectCallback;

    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> heartbeatTask =
            new AtomicReference<ScheduledFuture<?>>();

    interface DisconnectCallback {
        void onDisconnected(SseClientConnection connection, Throwable cause);
    }

    SseClientConnection(String clientId, AsyncContext asyncContext,
                        HttpServletResponse response, DisconnectCallback disconnectCallback) {
        this.clientId = clientId;
        this.asyncContext = asyncContext;
        this.response = response;
        this.disconnectCallback = disconnectCallback;
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * 发送完整事件帧（id/event/data）。多行 data 会按 SSE 规范拆分为多个 data 行。
     *
     * @return false 表示连接已关闭或写出失败（连接将被关闭）
     */
    public boolean sendEvent(String id, String event, String data) {
        return write(SseFrameBuilder.event(id, event, data));
    }

    /** 发送注释行（如自定义心跳内容）。 */
    public boolean sendComment(String comment) {
        return write(SseFrameBuilder.comment(comment));
    }

    void setHeartbeatTask(ScheduledFuture<?> task) {
        heartbeatTask.set(task);
    }

    /** 服务端主动关闭（幂等）。 */
    public void close() {
        close(null);
    }

    /** 包内帧写出通道（初始帧 retry: 使用）。 */
    boolean write(String frame) {
        synchronized (writeLock) {
            if (closed.get()) {
                return false;
            }
            try {
                response.getWriter().print(frame);
                response.getWriter().flush();
                if (response.getWriter().checkError()) {
                    throw new IOException("SSE 写出失败（PrintWriter 错误标志置位）");
                }
                return true;
            } catch (IOException | RuntimeException e) {
                log.debug("SSE 连接写出失败，关闭连接: clientId={}", clientId, e);
                close(e);
                return false;
            }
        }
    }

    private void close(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> task = heartbeatTask.getAndSet(null);
        if (task != null) {
            task.cancel(false);
        }
        try {
            asyncContext.complete();
        } catch (RuntimeException e) {
            log.debug("完成异步上下文失败（容器可能已自行结束）: clientId={}", clientId, e);
        }
        disconnectCallback.onDisconnected(this, cause);
    }

    /** SSE 帧构造与净荷清洗（防 framing 注入）。 */
    static final class SseFrameBuilder {

        private SseFrameBuilder() {
        }

        static String event(String id, String event, String data) {
            StringBuilder sb = new StringBuilder(64);
            if (id != null && !id.isEmpty()) {
                sb.append("id: ").append(sanitizeLine(id)).append('\n');
            }
            if (event != null && !event.isEmpty()) {
                sb.append("event: ").append(sanitizeLine(event)).append('\n');
            }
            String normalized = data == null ? "" : data.replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n", -1);
            for (String line : lines) {
                sb.append("data: ").append(line).append('\n');
            }
            sb.append('\n');
            return sb.toString();
        }

        static String comment(String comment) {
            return ":" + (comment == null ? "" : sanitizeLine(comment)) + "\n\n";
        }

        static String retry(long reconnectMs) {
            return "retry: " + reconnectMs + "\n\n";
        }

        /** id/event 字段不得包含 CR/LF，替换为空格防帧破坏。 */
        private static String sanitizeLine(String value) {
            return value.replace('\r', ' ').replace('\n', ' ');
        }
    }
}
