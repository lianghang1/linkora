package com.linkora.server.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;

/**
 * 服务端到单个客户端的 SSE 连接封装（非阻塞写模型）。
 *
 * <p>设计（docs/design/sse-async-write.md）：所有帧先进每连接出站队列，
 * 由容器 {@link WriteListener} 回调驱动非阻塞泵写出——任何客户端的写出问题
 * 只影响它自己的队列与连接，编排线程/心跳线程入队后立即返回。</p>
 *
 * <p>背压策略：队列字节量超过水位（构造参数）时主动断开本连接——客户端会自动
 * 重连并按 Last-Event-ID 补发，不丢数据。</p>
 *
 * <p>并发契约：同一连接的容器回调绝不并发（Servlet 3.1 规范）；泵的进入由
 * {@code flushing} CAS 仲裁，任意时刻至多一个线程在写；共享状态全部原子变量，无锁。
 * 写法参照 Tomcat 官方 Async IO 示例（NumberWriter）骨架。</p>
 *
 * @author lianghang
 */
public final class SseClientConnection {

    private static final Logger log = LoggerFactory.getLogger(SseClientConnection.class);

    /** 默认出站队列水位：8MB（与 ServerConfig 默认值一致，见 getMaxOutboundBytesPerConnection）。 */
    static final long DEFAULT_MAX_OUTBOUND_BYTES = 8L * 1024 * 1024;

    private final String clientId;
    private final AsyncContext asyncContext;
    private final DisconnectCallback disconnectCallback;
    private final ServletOutputStream output;
    /** 出站队列水位（字节）：超过即断开本连接（慢客户端处置）。 */
    private final long maxOutboundBytes;

    /** 出站帧队列（元素已编码为 UTF-8 字节）。 */
    private final LinkedBlockingQueue<byte[]> outbound = new LinkedBlockingQueue<byte[]>();
    private final AtomicLong outboundBytes = new AtomicLong(0);
    /** 泵仲裁：true 表示有线程正在 drain（容器回调线程或入队线程）。 */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 认证通过标记：置位前 broadcast 跳过该连接。连接先注册后认证的时序下，
     * 防止未认证连接在认证耗时窗口内经并发 sweep 收到目标 clientId 的消息内容。
     */
    private final AtomicBoolean readyForPush = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> heartbeatTask =
            new AtomicReference<ScheduledFuture<?>>();

    interface DisconnectCallback {
        void onDisconnected(SseClientConnection connection, Throwable cause);
    }

    SseClientConnection(String clientId, AsyncContext asyncContext,
                        HttpServletResponse response, DisconnectCallback disconnectCallback,
                        long maxOutboundBytes) throws IOException {
        if (maxOutboundBytes <= 0) {
            throw new IllegalArgumentException("maxOutboundBytes 必须 > 0");
        }
        this.clientId = clientId;
        this.asyncContext = asyncContext;
        this.disconnectCallback = disconnectCallback;
        this.maxOutboundBytes = maxOutboundBytes;
        // 非阻塞写只能走 ServletOutputStream（规范要求），帧统一 UTF-8 编码
        this.output = response.getOutputStream();
        output.setWriteListener(new PumpListener());
    }

    SseClientConnection(String clientId, AsyncContext asyncContext,
                        HttpServletResponse response, DisconnectCallback disconnectCallback)
            throws IOException {
        this(clientId, asyncContext, response, disconnectCallback, DEFAULT_MAX_OUTBOUND_BYTES);
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /**
     * 认证通过后由订阅 handler 置位；置位前广播推送跳过该连接。
     * 必须在提交重连补发之前调用，保证补发首推能命中本连接。
     */
    public void markReadyForPush() {
        readyForPush.set(true);
    }

    boolean isReadyForPush() {
        return readyForPush.get();
    }

    /**
     * 发送完整事件帧（id/event/data）。多行 data 会按 SSE 规范拆分为多个 data 行。
     *
     * @return false 表示连接已关闭、入队被拒或写出失败（连接将被关闭）
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
        // 连接可能在心跳任务注册前已 close（此时 close 读到 null 无法取消）：
        // 双检补偿取消，避免任务按固定周期空转并持有连接引用
        if (closed.get() && heartbeatTask.compareAndSet(task, null)) {
            task.cancel(false);
        }
    }

    /** 服务端主动关闭（幂等）。 */
    public void close() {
        close(null);
    }

    /**
     * 入队一帧并触发泵。永不阻塞：入队成功即返回 true
     * （delivered 语义 = 入队，未实际写出由重连补发/周期 sweep 兜底）。
     * 队列超水位时主动断开本连接（慢客户端处置）并返回 false。
     */
    boolean write(String frame) {
        if (closed.get()) {
            return false;
        }
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (outboundBytes.addAndGet(bytes.length) > maxOutboundBytes) {
            outboundBytes.addAndGet(-bytes.length);
            log.warn("SSE 出站队列超过水位（{} bytes），主动断开慢客户端: clientId={}",
                    maxOutboundBytes, clientId);
            close(new IOException("出站队列水位超限"));
            return false;
        }
        if (!outbound.offer(bytes)) {
            outboundBytes.addAndGet(-bytes.length);
            close(new IOException("出站队列拒绝入队"));
            return false;
        }
        tryPump();
        return !closed.get();
    }

    /** 触发泵：CAS 仲裁保证任意时刻至多一个线程在 drain。 */
    private void tryPump() {
        if (closed.get()) {
            return;
        }
        if (flushing.compareAndSet(false, true)) {
            try {
                drain();
            } finally {
                flushing.set(false);
            }
            // 防丢唤起：drain 期间新帧入队且容器不会再回调时，由这里补一次泵
            if (!outbound.isEmpty() && !closed.get()
                    && flushing.compareAndSet(false, true)) {
                try {
                    drain();
                } finally {
                    flushing.set(false);
                }
            }
        }
        // CAS 失败 = 已有线程在 drain，队列会被它或容器回调消化
    }

    /**
     * 非阻塞 drain（Tomcat 官方 Async IO 示例骨架）：能写则取帧写，
     * 写不动则退出等容器回调；队列空则自然结束。
     */
    private void drain() {
        try {
            while (!closed.get()) {
                if (outbound.isEmpty()) {
                    break;
                }
                if (!output.isReady()) {
                    return; // 容器会在可写时回调 onWritePossible
                }
                byte[] frame = outbound.poll();
                if (frame == null) {
                    break;
                }
                output.write(frame);
            }
            if (output.isReady()) {
                output.flush(); // 推出已交给容器的剩余字节
            }
        } catch (IOException e) {
            log.debug("SSE 非阻塞写出失败，关闭连接: clientId={}", clientId, e);
            close(e);
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
        outbound.clear();
        try {
            asyncContext.complete();
        } catch (RuntimeException e) {
            log.debug("完成异步上下文失败（容器可能已自行结束）: clientId={}", clientId, e);
        }
        disconnectCallback.onDisconnected(this, cause);
    }

    /** 容器回调：可写时驱动泵。 */
    private final class PumpListener implements WriteListener {
        @Override
        public void onWritePossible() {
            if (closed.get()) {
                return;
            }
            if (flushing.compareAndSet(false, true)) {
                try {
                    drain();
                } finally {
                    flushing.set(false);
                }
                if (!outbound.isEmpty() && !closed.get()
                        && flushing.compareAndSet(false, true)) {
                    try {
                        drain();
                    } finally {
                        flushing.set(false);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            log.debug("SSE 容器回调错误，关闭连接: clientId={}", clientId, t);
            close(t);
        }
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
