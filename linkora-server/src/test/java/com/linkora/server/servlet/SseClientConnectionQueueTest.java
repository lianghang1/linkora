package com.linkora.server.servlet;

import org.junit.Test;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SSE 连接写出队列测试：入队不阻塞、队列满即断开、FIFO 顺序、写超时判定。
 *
 * <p>用动态代理伪造 Servlet 的 {@link HttpServletResponse} / {@link AsyncContext}，
 * 不需要容器；阻塞写用一个可控的 {@link Writer} 模拟"TCP 发送窗口打满"。</p>
 *
 * @author lianghang
 */
public class SseClientConnectionQueueTest {

    private final ExecutorService writerPool = Executors.newFixedThreadPool(2);
    private final AtomicBoolean completed = new AtomicBoolean(false);

    // ---------------- 用例 ----------------

    @Test
    public void enqueueNeverBlocksCallerAndClosesWhenQueueFull() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        SseClientConnection connection = connection(new BlockingWriter(release),
                2, new AtomicInteger());

        long start = System.nanoTime();
        boolean closedByQueue = false;
        for (int i = 0; i < 100; i++) {
            if (!connection.sendEvent(String.valueOf(i), "msg", "payload")) {
                closedByQueue = true;
                break;
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("队列满时必须主动断开而不是无限缓冲", closedByQueue);
        assertFalse("断开后连接不可用", connection.isOpen());
        assertTrue("入队必须是纯内存操作，不能被阻塞写拖住，实测 " + elapsedMs + " ms",
                elapsedMs < 1_000L);

        release.countDown();
    }

    @Test
    public void framesAreDeliveredInFifoOrder() throws Exception {
        StringWriter sink = new StringWriter();
        SseClientConnection connection = connection(sink, 16, new AtomicInteger());

        assertTrue(connection.sendEvent("1", "msg", "first"));
        assertTrue(connection.sendEvent("2", "msg", "second"));
        assertTrue(connection.sendEvent("3", "msg", "third"));

        long deadline = System.currentTimeMillis() + 3_000L;
        while (sink.toString().indexOf("third") < 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        String written = sink.toString();
        assertTrue(written.contains("first") && written.contains("second") && written.contains("third"));
        assertTrue("帧必须按入队顺序写出", written.indexOf("first") < written.indexOf("second")
                && written.indexOf("second") < written.indexOf("third"));
        // id 顺序一并校验（客户端靠它检测空洞）
        assertTrue(written.indexOf("id: 1") < written.indexOf("id: 2"));
        assertTrue(connection.isOpen());
        connection.close();
    }

    @Test
    public void writeStallIsDetectedForWatchdog() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        SseClientConnection connection = connection(new BlockingWriter(release),
                8, new AtomicInteger());

        connection.sendEvent("1", "msg", "blocked");
        long deadline = System.currentTimeMillis() + 3_000L;
        while (!connection.isWriteStalled(System.currentTimeMillis(), 0L)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        // 写已开始且超过阈值 → 看门狗可据此关闭
        assertTrue("卡住的 flush 必须被识别为僵死写",
                connection.isWriteStalled(System.currentTimeMillis(), 1L));
        assertFalse("未超过阈值时不应误判",
                connection.isWriteStalled(System.currentTimeMillis(), 60_000L));
        assertTrue("连接此时仍处于打开状态，等待看门狗处置", connection.isOpen());

        release.countDown();
        connection.close();
        assertFalse(connection.isOpen());
    }

    @Test
    public void closeTriggersCallbackOnceAndDropsPendingFrames() throws Exception {
        final AtomicInteger disconnects = new AtomicInteger();
        final CountDownLatch release = new CountDownLatch(1);
        SseClientConnection connection = connection(new BlockingWriter(release), 8, disconnects);

        connection.sendEvent("1", "msg", "x");
        connection.close();
        connection.close();
        assertEquals("断连回调必须只触发一次", 1, disconnects.get());
        assertTrue("关闭必须完成异步上下文", completed.get());
        assertEquals(0, connection.pendingFrames());

        release.countDown();
    }

    @Test
    public void oversizedFrameHonorsByteBudgetNotJustFrameCount() {
        // 帧数闸门（capacity=100）挡不住大消息：单帧 1.37MB 时 100 帧就是 137MB。
        // 字节闸门（8MB）必须先生效。
        AtomicInteger disconnects = new AtomicInteger();
        SseClientConnection connection = connection(new java.io.StringWriter(), 100, disconnects);

        StringBuilder huge = new StringBuilder(9 * 1024 * 1024);
        for (int i = 0; i < 9 * 1024 * 1024; i++) {
            huge.append('x');
        }

        assertFalse("超过字节预算必须拒绝入队", connection.sendEvent("1", "message", huge.toString()));
        assertFalse("字节超限应断开连接，靠重连补发", connection.isOpen());
        assertEquals(1, disconnects.get());
    }

    // ---------------- 构造与桩 ----------------

    private SseClientConnection connection(Writer sink, int capacity, final AtomicInteger disconnects) {
        final PrintWriter writer = new PrintWriter(sink);
        HttpServletResponse response = responseReturningWriter(writer);
        AsyncContext asyncContext = asyncContext();
        return new SseClientConnection("store-001", asyncContext, response,
                new SseClientConnection.DisconnectCallback() {
                    @Override
                    public void onDisconnected(SseClientConnection connection, Throwable cause) {
                        disconnects.incrementAndGet();
                    }
                }, writerPool, capacity);
    }

    private HttpServletResponse responseReturningWriter(final PrintWriter writer) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getWriter".equals(method.getName())) {
                    return writer;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, handler);
    }

    private AsyncContext asyncContext() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("complete".equals(method.getName())) {
                    completed.set(true);
                    return null;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return (AsyncContext) Proxy.newProxyInstance(
                AsyncContext.class.getClassLoader(),
                new Class<?>[]{AsyncContext.class}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return null;
    }

    /** 模拟 TCP 发送窗口打满：flush / write 一直阻塞到 release 放行。 */
    private static final class BlockingWriter extends Writer {

        private final CountDownLatch release;

        BlockingWriter(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            awaitRelease();
        }

        @Override
        public void flush() throws IOException {
            awaitRelease();
        }

        @Override
        public void close() {
        }

        private void awaitRelease() throws IOException {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("写入被中断", e);
            }
        }
    }
}
