package com.linkora.server.servlet;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SsePushServlet 嵌入式集成测试（Jetty 9.4 仅为测试容器，生产走宿主容器）。
 *
 * @author lianghang
 */
public class SsePushServletJettyTest {

    private static final long AWAIT_SECONDS = 5;

    private Server server;
    private RecordingHandler handler;
    private SseConnectionRegistry registry;
    private OkHttpClient httpClient;

    @Before
    public void setUp() throws Exception {
        handler = new RecordingHandler();
        registry = new SseConnectionRegistry();
        SsePushServlet servlet = new SsePushServlet(handler, registry, 100L, 3000L);

        server = new Server(0);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(servlet), "/linkora/sse/*");
        server.setHandler(context);
        server.start();

        httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
    }

    private String url(String clientId) {
        return "http://localhost:" + server.getURI().getPort() + "/linkora/sse?clientId=" + clientId;
    }

    private EventSource connect(String clientId, String lastEventId, EventSourceListener listener) {
        Request.Builder builder = new Request.Builder().url(url(clientId));
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId);
        }
        return EventSources.createFactory(httpClient).newEventSource(builder.build(), listener);
    }

    /** 等待服务端完成注册（连接对象可用）。 */
    private SseClientConnection awaitServerConnection() throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            SseClientConnection connection = handler.lastConnection.get();
            if (connection != null && registry.totalConnections() > 0) {
                return connection;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("等待服务端连接注册超时");
    }

    @Test
    public void subscribeAndReceiveEvent() throws Exception {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<String>();
        EventSource source = connect("store-001", null, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                received.add(id + "|" + type + "|" + data);
            }
        });

        SseClientConnection connection = awaitServerConnection();
        assertEquals("store-001", connection.getClientId());
        assertNotNull(handler.lastClientId.get());

        assertTrue(connection.sendEvent("101", "ORDER_CREATED", "{\"orderId\":9}"));

        String raw = received.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("101|ORDER_CREATED|{\"orderId\":9}", raw);

        source.cancel();
    }

    @Test
    public void lastEventIdForwardedToHandler() throws Exception {
        final CountDownLatch subscribed = new CountDownLatch(1);
        final AtomicReference<String> captured = new AtomicReference<String>();
        handler.onSubscribeHook = new SubscribeHook() {
            @Override
            public boolean onSubscribe(String clientId, String lastEventId,
                                       java.util.Map<String, String> headers, SseClientConnection c) {
                captured.set(lastEventId);
                subscribed.countDown();
                return true;
            }
        };

        EventSource source = connect("store-002", "42", new EventSourceListener() {
        });

        assertTrue(subscribed.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("42", captured.get());
        source.cancel();
    }

    @Test
    public void heartbeatEventReceived() throws Exception {
        final LinkedBlockingQueue<String> heartbeats = new LinkedBlockingQueue<String>();
        EventSource source = connect("store-003", null, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                if (SsePushServlet.SseHeartbeat.EVENT_TYPE.equals(type)) {
                    heartbeats.add(type);
                }
            }
        });

        String heartbeat = heartbeats.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(SsePushServlet.SseHeartbeat.EVENT_TYPE, heartbeat);
        source.cancel();
    }

    @Test
    public void multiLineDataIsPreserved() throws Exception {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<String>();
        EventSource source = connect("store-004", null, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                received.add(data);
            }
        });

        SseClientConnection connection = awaitServerConnection();
        assertTrue(connection.sendEvent("1", "T", "line1\nline2\nline3"));

        String data = received.poll(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals("line1\nline2\nline3", data);
        source.cancel();
    }

    @Test
    public void rejectedSubscribeReturns401() throws Exception {
        handler.rejectAll = true;
        final CountDownLatch failed = new CountDownLatch(1);
        final AtomicReference<Integer> httpCode = new AtomicReference<Integer>();

        Request request = new Request.Builder().url(url("store-005")).build();
        EventSources.createFactory(httpClient).newEventSource(request, new EventSourceListener() {
            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                if (response != null) {
                    httpCode.set(response.code());
                }
                failed.countDown();
            }
        });

        assertTrue(failed.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(Integer.valueOf(401), httpCode.get());
        assertEquals(0, registry.totalConnections());
    }

    @Test
    public void missingClientIdReturns400() throws Exception {
        Request request = new Request.Builder()
                .url("http://localhost:" + server.getURI().getPort() + "/linkora/sse")
                .build();
        Response response = httpClient.newCall(request).execute();
        assertEquals(400, response.code());
        response.close();
    }

    @Test
    public void clientDisconnectNotifiesHandler() throws Exception {
        EventSource source = connect("store-006", null, new EventSourceListener() {
        });

        awaitServerConnection();
        final CountDownLatch disconnected = new CountDownLatch(1);
        handler.onDisconnectHook = new DisconnectHook() {
            @Override
            public void onDisconnect(String clientId, SseClientConnection c, Throwable cause) {
                disconnected.countDown();
            }
        };

        source.cancel();

        assertTrue(disconnected.await(AWAIT_SECONDS, TimeUnit.SECONDS));
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(AWAIT_SECONDS);
        while (registry.totalConnections() > 0
                && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertEquals(0, registry.totalConnections());
    }

    @Test
    public void serverSideCloseCompletesClient() throws Exception {
        final CountDownLatch closed = new CountDownLatch(1);
        EventSource source = connect("store-007", null, new EventSourceListener() {
            @Override
            public void onClosed(EventSource es) {
                closed.countDown();
            }
        });

        SseClientConnection connection = awaitServerConnection();
        connection.close();

        assertTrue(closed.await(AWAIT_SECONDS, TimeUnit.SECONDS));
    }

    @Test
    public void registryBroadcastReachesConnection() throws Exception {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<String>();
        EventSource source = connect("store-008", null, new EventSourceListener() {
            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                received.add(data);
            }
        });

        awaitServerConnection();
        int delivered = registry.broadcast("store-008", "9", "PING", "via-registry");
        assertEquals(1, delivered);
        assertEquals("via-registry", received.poll(AWAIT_SECONDS, TimeUnit.SECONDS));

        assertEquals(0, registry.broadcast("offline-client", "1", "PING", "nobody"));
        assertEquals(1, registry.activeClientIds().size());
        source.cancel();
    }

    // ---- 测试用记录型 handler ----

    private interface SubscribeHook {
        boolean onSubscribe(String clientId, String lastEventId,
                            java.util.Map<String, String> headers, SseClientConnection connection);
    }

    private interface DisconnectHook {
        void onDisconnect(String clientId, SseClientConnection connection, Throwable cause);
    }

    private static final class RecordingHandler implements SseSubscribeHandler {
        volatile boolean rejectAll = false;
        volatile SubscribeHook onSubscribeHook;
        volatile DisconnectHook onDisconnectHook;
        final AtomicReference<String> lastClientId = new AtomicReference<String>();
        final AtomicReference<SseClientConnection> lastConnection =
                new AtomicReference<SseClientConnection>();

        @Override
        public boolean onSubscribe(String clientId, String lastEventId,
                                   java.util.Map<String, String> headers,
                                   SseClientConnection connection) {
            lastClientId.set(clientId);
            lastConnection.set(connection);
            if (onSubscribeHook != null) {
                return onSubscribeHook.onSubscribe(clientId, lastEventId, headers, connection);
            }
            return !rejectAll;
        }

        @Override
        public void onDisconnect(String clientId, SseClientConnection connection, Throwable cause) {
            if (onDisconnectHook != null) {
                onDisconnectHook.onDisconnect(clientId, connection, cause);
            }
        }
    }
}
