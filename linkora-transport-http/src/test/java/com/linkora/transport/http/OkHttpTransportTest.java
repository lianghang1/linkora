package com.linkora.transport.http;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * OkHttpTransport 单元测试（基于 MockWebServer，无真实网络）。
 *
 * @author lianghang
 */
public class OkHttpTransportTest {

    private MockWebServer server;
    private OkHttpTransport transport;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        transport = OkHttpTransport.builder().build();
    }

    @After
    public void tearDown() throws Exception {
        transport.close();
        server.shutdown();
    }

    private String url() {
        return server.url("/linkora/messages").toString();
    }

    @Test
    public void postSuccessReturnsCodeBodyAndHeaders() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Linkora-Message-Id", "m-1")
                .setBody("{\"accepted\":true}"));

        TransportRequest request = TransportRequest.newBuilder(url())
                .header("Content-Type", "application/json")
                .header("X-Linkora-Protocol", "1")
                .body("{\"messageType\":\"X\"}".getBytes(StandardCharsets.UTF_8))
                .build();

        TransportResponse response = transport.execute(request);

        assertTrue(response.isSuccessful());
        assertEquals(200, response.getCode());
        assertEquals("{\"accepted\":true}", new String(response.getBody(), StandardCharsets.UTF_8));
        assertEquals("m-1", response.header("X-Linkora-Message-Id"));

        RecordedRequest recorded = server.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("1", recorded.getHeader("X-Linkora-Protocol"));
        assertEquals("{\"messageType\":\"X\"}", recorded.getBody().readUtf8());
    }

    @Test
    public void clientErrorIsNonRetryableAndBodyPreserved() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"code\":\"INVALID_MESSAGE\"}"));

        TransportResponse response = transport.execute(
                TransportRequest.newBuilder(url()).body(new byte[]{1}).build());

        assertFalse(response.isSuccessful());
        TransportException ex = TransportException.fromResponse(response);
        assertEquals(TransportErrorType.CLIENT_ERROR, ex.getErrorType());
        assertFalse(ex.isRetryable());
        assertEquals(Integer.valueOf(400), ex.getHttpCode());
    }

    @Test
    public void rateLimitedParsesRetryAfter() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "3")
                .setBody("slow down"));

        TransportResponse response = transport.execute(
                TransportRequest.newBuilder(url()).body(new byte[]{1}).build());

        TransportException ex = TransportException.fromResponse(response);
        assertEquals(TransportErrorType.RATE_LIMITED, ex.getErrorType());
        assertTrue(ex.isRetryable());
        assertEquals(Long.valueOf(3000L), ex.getRetryAfterMs());
    }

    @Test
    public void serverErrorIsRetryable() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));

        TransportResponse response = transport.execute(
                TransportRequest.newBuilder(url()).body(new byte[]{1}).build());

        TransportException ex = TransportException.fromResponse(response);
        assertEquals(TransportErrorType.SERVER_ERROR, ex.getErrorType());
        assertTrue(ex.isRetryable());
        assertNull(ex.getRetryAfterMs());
    }

    @Test
    public void httpDateRetryAfterIsIgnored() {
        assertNull(TransportException.parseRetryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"));
        assertNull(TransportException.parseRetryAfterSeconds(null));
        assertEquals(Long.valueOf(1000L), TransportException.parseRetryAfterSeconds("1"));
    }

    @Test
    public void connectionResetIsNetworkError() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        try {
            transport.execute(TransportRequest.newBuilder(url()).body(new byte[]{1}).build());
            fail("应当抛出 TransportException");
        } catch (TransportException ex) {
            assertEquals(TransportErrorType.NETWORK_ERROR, ex.getErrorType());
            assertTrue(ex.isRetryable());
        }
    }

    @Test
    public void readTimeoutIsTimeoutError() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("delayed")
                .throttleBody(1, 2, TimeUnit.SECONDS));

        OkHttpTransport shortTimeout = OkHttpTransport.builder()
                .maxResponseBytes(1024 * 1024)
                .build();
        try {
            TransportRequest request = TransportRequest.newBuilder(url())
                    .body(new byte[]{1})
                    .readTimeoutMs(300L)
                    .build();
            shortTimeout.execute(request);
            fail("应当抛出超时异常");
        } catch (TransportException ex) {
            assertEquals(TransportErrorType.TIMEOUT, ex.getErrorType());
            assertTrue(ex.isRetryable());
        } finally {
            shortTimeout.close();
        }
    }

    @Test
    public void oversizedResponseIsRejected() throws Exception {
        server.enqueue(new MockResponse().setBody("0123456789"));

        OkHttpTransport tiny = OkHttpTransport.builder().maxResponseBytes(4).build();
        try {
            tiny.execute(TransportRequest.newBuilder(url()).body(new byte[]{1}).build());
            fail("应当抛出响应超限异常");
        } catch (TransportException ex) {
            assertEquals(TransportErrorType.RESPONSE_TOO_LARGE, ex.getErrorType());
            assertFalse(ex.isRetryable());
        } finally {
            tiny.close();
        }
    }

    @Test
    public void closeRejectsFurtherRequests() throws Exception {
        transport.close();
        try {
            transport.execute(TransportRequest.newBuilder(url()).body(new byte[]{1}).build());
            fail("关闭后应当拒绝请求");
        } catch (TransportException ex) {
            assertEquals(TransportErrorType.NETWORK_ERROR, ex.getErrorType());
        }
    }

    @Test
    public void requestDefensiveCopyPreventsMutation() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        byte[] payload = "secret".getBytes(StandardCharsets.UTF_8);
        TransportRequest request = TransportRequest.newBuilder(url())
                .body(payload)
                .build();
        payload[0] = 'X';

        TransportResponse response = transport.execute(request);
        assertTrue(response.isSuccessful());
        RecordedRequest recorded = server.takeRequest();
        assertEquals("secret", recorded.getBody().readUtf8());
        assertArrayEquals("响应体拷贝应与原响应一致",
                "ok".getBytes(StandardCharsets.UTF_8), response.getBody());
    }
}
