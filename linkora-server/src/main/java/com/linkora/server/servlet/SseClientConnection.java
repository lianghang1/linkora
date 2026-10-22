package com.linkora.server.servlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;

/**
 * 服务端到单个客户端的 SSE 连接封装。
 *
 * <p>写出模型（背压的核心）：</p>
 * <ul>
 *   <li>{@link #sendEvent} / {@link #sendComment} 只做<b>入队</b>，不阻塞调用方——
 *       调用方是单线程的投递编排器，绝不能被某个客户端的 TCP 发送窗口拖住；</li>
 *   <li>每个连接有独立的有界队列（默认 1024 帧）与"同一时刻只有一个排空任务"的约束，
 *       由共享写线程池执行实际 {@code flush()}；FIFO 保证帧顺序不变；</li>
 *   <li>队列满说明该客户端长期跟不上：<b>主动断开连接</b>，由客户端重连后的补发恢复，
 *       绝不无限缓冲（未 ACK 的消息仍在 lk_delivery 里，断开不丢消息）；</li>
 *   <li>写线程池与心跳调度器分离，且心跳也走队列，因此单个卡死的 socket
 *       最多占用一个池线程，不会阻塞其他客户端与编排线程。</li>
 * </ul>
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
    /** 实际执行 flush 的共享写线程池（由 servlet 持有与关闭）。 */
    private final Executor writerExecutor;

    /** 待写出帧队列：有界，满即断开。 */
    private final ArrayBlockingQueue<String> pending;
    private final int queueCapacity;

    private final Object writeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 认证通过标记：置位前 broadcast 跳过该连接。连接先注册后认证的时序下，
     * 防止未认证连接在认证耗时窗口内经并发 sweep 收到目标 clientId 的消息内容。
     */
    private final AtomicBoolean readyForPush = new AtomicBoolean(false);
    /** 排空任务在途标记：保证同一连接同一时刻只有一个线程在写（FIFO 不被打乱）。 */
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicReference<ScheduledFuture<?>> heartbeatTask =
            new AtomicReference<ScheduledFuture<?>>();

    /**
     * 单连接排队帧的字节上限（第二道闸）。
     *
     * <p>队列按帧数限流（默认 1024），但单帧大小由 payload 决定：1MB payload 的帧约
     * 1.37MB，1024 帧即 1.4GB——帧数闸门对大消息形同虚设。这里按累计字节再挡一次，
     * 超过即断开（消息仍在 lk_delivery，重连补发恢复）。</p>
     */
    private static final long MAX_QUEUED_BYTES = 8L * 1024 * 1024;

    /** 已入队但尚未写出的帧的累计字节（UTF-16 char 数近似，base64 载荷 1:1）。 */
    private final java.util.concurrent.atomic.AtomicLong queuedBytes =
            new java.util.concurrent.atomic.AtomicLong();

    /** 当前这次 flush 的开始时间（0 表示空闲），供写超时看门狗判定僵尸连接。 */
    private volatile long writingSince = 0L;

    interface DisconnectCallback {
        void onDisconnected(SseClientConnection connection, Throwable cause);
    }

    SseClientConnection(String clientId, AsyncContext asyncContext,
                        HttpServletResponse response, DisconnectCallback disconnectCallback,
                        Executor writerExecutor, int queueCapacity) {
        if (writerExecutor == null) {
            throw new IllegalArgumentException("writerExecutor 不能为 null");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity 必须为正数");
        }
        this.clientId = clientId;
        this.asyncContext = asyncContext;
        this.response = response;
        this.disconnectCallback = disconnectCallback;
        this.writerExecutor = writerExecutor;
        this.queueCapacity = queueCapacity;
        this.pending = new ArrayBlockingQueue<String>(queueCapacity);
    }

    public String getClientId() {
        return clientId;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** 当前排队待写出的帧数（监控用）。 */
    public int pendingFrames() {
        return pending.size();
    }

    /**
     * 判定该连接是否"写操作卡住超过 timeoutMs"：TCP 发送窗口打满时 flush 会长时间阻塞，
     * 此时连接实际上已不可用，由看门狗关闭（消息仍会重投）。
     */
    public boolean isWriteStalled(long now, long timeoutMs) {
        long since = writingSince;
        return since > 0 && now - since > timeoutMs;
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
     * @return true 表示已入队（<b>不代表已写出</b>）；false 表示连接已关闭或队列已满（连接随即关闭）
     */
    public boolean sendEvent(String id, String event, String data) {
        return enqueue(SseFrameBuilder.event(id, event, data));
    }

    /** 发送注释行（如自定义心跳内容）。 */
    public boolean sendComment(String comment) {
        return enqueue(SseFrameBuilder.comment(comment));
    }

    /** 发送原始帧（初始 {@code retry:} 用）。 */
    boolean sendRawFrame(String frame) {
        return enqueue(frame);
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

    // ---------------- 入队与排空 ----------------

    private boolean enqueue(String frame) {
        if (closed.get()) {
            return false;
        }
        long size = frame.length();
        if (queuedBytes.addAndGet(size) > MAX_QUEUED_BYTES) {
            queuedBytes.addAndGet(-size);
            log.warn("SSE 待写字节超过 {}MB，主动断开连接: clientId={}",
                    MAX_QUEUED_BYTES >> 20, clientId);
            close(new IOException("SSE 待写字节超过 " + (MAX_QUEUED_BYTES >> 20) + "MB"));
            return false;
        }
        if (!pending.offer(frame)) {
            // 队列满：该客户端已长期跟不上，主动断开靠重连补发恢复，绝不无限缓冲
            queuedBytes.addAndGet(-size);
            log.warn("SSE 发送队列已满（容量 {}），主动断开连接: clientId={}",
                    queueCapacity, clientId);
            close(new IOException("SSE 发送队列已满（容量 " + queueCapacity + "）"));
            return false;
        }
        scheduleDrain();
        return true;
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return; // 已有排空任务在途，由它继续消费（FIFO 不变）
        }
        try {
            writerExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            });
        } catch (RejectedExecutionException e) {
            // 写线程池已关闭（服务端停机）：连接即将被统一关闭，消息靠重投恢复
            draining.set(false);
        }
    }

    /** 排空队列。任何一次写出失败都会关闭连接并停止排空。 */
    private void drain() {
        try {
            String frame;
            while (!closed.get() && (frame = pending.poll()) != null) {
                queuedBytes.addAndGet(-frame.length());
                if (!write(frame)) {
                    return;
                }
            }
        } finally {
            draining.set(false);
            // 双检：drain 收尾与 enqueue 之间存在"新帧入队但读到的 draining 仍为 true"的窗口
            if (!pending.isEmpty() && !closed.get()) {
                scheduleDrain();
            }
        }
    }

    /** 包内帧写出通道（仅由排空线程或初始帧调用）。 */
    boolean write(String frame) {
        synchronized (writeLock) {
            if (closed.get()) {
                return false;
            }
            writingSince = System.currentTimeMillis();
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
            } finally {
                writingSince = 0L;
            }
        }
    }

    private void close(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.clear();
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
