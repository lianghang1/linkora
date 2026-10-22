package com.linkora.transport.sse;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * SseStreamClient 单元测试（基于 MockWebServer，无真实网络）。
 *
 * @author lianghang
 */
public class SseStreamClientTest {

    private static final long AWAIT_SECONDS = 5;

    private MockWebServer server;
    private SseStreamClient client;

    private final LinkedBlockingQueue<String> events = new LinkedBlockingQueue<String>();
    private volatile Integer lastFailureHttpCode;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        server.shutdown();
    }

    private String url() {
        return server.url("/linkora/sse").toString();
    }

    /** 固定 10ms 快速退避，保证测试确定性。 */
    private static final ReconnectPolicy FAST_POLICY = new ReconnectPolicy() {
        @Override
        public long nextDelayMs(int attempt, Long serverSuggestedMs) {
            return 10L;
        }
    };

    /** 拉取一个事件并按 id/data 断言（type 字段不参与断言）。 */
    private void assertEvent(String expectedId, String expectedData) throws InterruptedException {
        String raw = events.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue("应在限定时间内收到事件，实际: " + raw, raw != null);
        String[] parts = raw.split("\\|");
        // id 为 null 时事件串按 toString 记为 "null"
        assertEquals("事件 id (raw=" + raw + ")", String.valueOf(expectedId), parts[0]);
        assertEquals("事件 data", expectedData, parts[2]);
    }

    /**
     * 轮询等待连接进入期望状态：状态转换在单线程调度器上异步执行，且 onOpen 与
     * onEvent 从不同 OkHttp 线程 post、提交顺序无保证，事件可能先于 OPEN 可见、
     * close 后 CLOSED 可能稍后可见——故不断言瞬时值只轮询终态。
     */
    private void awaitState(SseStreamClient sse, SseConnectionState expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000;
        while (sse.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }

    private SseStreamClient newClient() {
        return newClient(Collections.<String, String>emptyMap());
    }

    private SseStreamClient newClient(final Map<String, String> extraHeaders) {
        SseStreamClient built = SseStreamClient.builder()
                .endpoint(url())
                .reconnectPolicy(FAST_POLICY)
                .idleTimeoutMs(0)
                .listener(new SseListener() {
                    @Override
                    public void onOpen() {
                        // 无需记录
                    }

                    @Override
                    public void onEvent(String id, String type, String data) {
                        events.add(id + "|" + type + "|" + data);
                    }

                    @Override
                    public void onClosed() {
                        // 流关闭由重连行为间接验证，不占用事件队列
                    }

                    @Override
                    public void onFailure(Throwable error, Integer httpCode) {
                        lastFailureHttpCode = httpCode;
                    }
                })
                .headersProvider(new SseStreamClient.HeadersProvider() {
                    @Override
                    public Map<String, String> headersForConnection() {
                        return new LinkedHashMap<String, String>(extraHeaders);
                    }
                })
                .build();
        client = built;
        return built;
    }

    @Test
    public void receivesEventsIncludingHeartbeat() throws Exception {
        // 心跳按两端约定以 event: hb 事件发送（okhttp-sse 不透出注释行）。
        // 字节限流延迟流 EOF：否则 mock 数据读完立即重连，OPEN 状态与
        // 重连后的 CONNECTING 存在轮询竞态，断言结果取决于线程调度快慢。
        server.enqueue(sseResponse("id: 1\nevent: ORDER_CREATED\ndata: hello\n\n"
                + "event: hb\ndata: hb\n\ndata: second\n\n")
                .throttleBody(16L, 100L, TimeUnit.MILLISECONDS));

        SseStreamClient sse = newClient();
        sse.start();

        assertEvent("1", "hello");
        String heartbeat = events.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertTrue("心跳事件应到达且类型为 hb: " + heartbeat,
                heartbeat.split("\\|")[1].contains("hb"));
        // SSE 规范：Last Event ID 具有粘性，未带 id 的后续事件沿用最近 id
        assertEvent("1", "second");
        awaitState(sse, SseConnectionState.OPEN);
        assertEquals(SseConnectionState.OPEN, sse.getState());
        assertEquals("1", sse.getLastEventId());
    }

    @Test
    public void reconnectsCarryingLastEventIdAndHeaders() throws Exception {
        server.enqueue(sseResponse("id: 42\ndata: first\n\n"));
        server.enqueue(sseResponse("data: second\n\n"));

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Linkora-Client-Id", "store-001");
        headers.put("Authorization", "Bearer test-token");
        SseStreamClient sse = newClient(headers);
        sse.start();

        assertEvent("42", "first");

        // 服务端流结束 → 自动重连（新连接 Reader 的 id 从头开始，Last-Event-ID 由请求头承载）
        assertEvent(null, "second");

        RecordedRequest second;
        server.takeRequest(); // 跳过首连请求，检查重连请求头
        second = server.takeRequest(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("42", second.getHeader("Last-Event-ID"));
        assertEquals("store-001", second.getHeader("X-Linkora-Client-Id"));
        assertEquals("Bearer test-token", second.getHeader("Authorization"));
    }

    @Test
    public void reconnectsAfterHttpFailure() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(sseResponse("data: recovered\n\n"));

        SseStreamClient sse = newClient();
        sse.start();

        assertEvent(null, "recovered");
        assertEquals(Integer.valueOf(500), lastFailureHttpCode);

        server.takeRequest(); // 跳过失败的 500 请求，检查重连请求头
        RecordedRequest second = server.takeRequest(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertNull("失败时未收到事件，不应携带游标", second.getHeader("Last-Event-ID"));
    }

    @Test
    public void manualCloseStopsReconnect() throws Exception {
        // 静默保持打开的流：事件 + 极慢填充（首个 50 字节块 1 秒内到达）
        server.enqueue(sseResponse("data: a\n\n" + spaces(512))
                .throttleBody(50, 1, TimeUnit.SECONDS));

        SseStreamClient sse = newClient();
        sse.start();
        assertEvent(null, "a");

        sse.close();
        awaitState(sse, SseConnectionState.CLOSED);
        assertEquals(SseConnectionState.CLOSED, sse.getState());

        TimeUnit.MILLISECONDS.sleep(500);
        assertEquals("手动关闭后不得重连", 1, server.getRequestCount());
    }

    @Test
    public void idleTimeoutTriggersReconnect() throws Exception {
        SseStreamClient idleClient = SseStreamClient.builder()
                .endpoint(url())
                .reconnectPolicy(FAST_POLICY)
                .idleTimeoutMs(300)
                .listener(new SseListener() {
                    @Override
                    public void onOpen() {
                    }

                    @Override
                    public void onEvent(String id, String type, String data) {
                        events.add(data);
                    }

                    @Override
                    public void onClosed() {
                    }

                    @Override
                    public void onFailure(Throwable error, Integer httpCode) {
                    }
                })
                .build();
        client = idleClient;

        // 流先送出事件，随后 1 秒 50 字节的静默填充 → 空闲检测判定死链并重连
        server.enqueue(sseResponse("data: before-idle\n\n" + spaces(2048))
                .throttleBody(50, 1, TimeUnit.SECONDS));
        server.enqueue(sseResponse("data: after-idle\n\n"));

        idleClient.start();

        assertEquals("before-idle", events.poll(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("after-idle", events.poll(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue("应发生第二次请求", server.getRequestCount() >= 2);
    }

    @Test
    public void startIsIdempotent() throws Exception {
        server.enqueue(sseResponse("data: a\n\n" + spaces(512))
                .throttleBody(50, 1, TimeUnit.SECONDS));

        SseStreamClient sse = newClient();
        sse.start();
        sse.start();
        sse.start();

        assertEvent(null, "a");
        TimeUnit.MILLISECONDS.sleep(500);
        assertEquals("重复 start 不得创建重复连接", 1, server.getRequestCount());
    }

    private static String spaces(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
