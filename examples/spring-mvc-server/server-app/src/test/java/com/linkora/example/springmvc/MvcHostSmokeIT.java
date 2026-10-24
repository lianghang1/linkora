package com.linkora.example.springmvc;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.message.OutboxStatus;
import com.linkora.testkit.RecordingMessageListener;
import org.apache.catalina.startup.Tomcat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 宿主装配烟测（Spring MVC / 内嵌 Tomcat）：走真实 SpringMvcServerMain 装配路径
 * 启动完整服务，真实客户端经真实 SSE + HTTP 完成下行与上行往返。
 *
 * @author lianghang
 */
public class MvcHostSmokeIT {

    private static final String CLIENT_ID = "demo-client";

    private Tomcat tomcat;
    private int port;
    private LinkoraClient client;
    private SqliteClientStore store;

    @Before
    public void setUp() throws Exception {
        port = freePort();
        tomcat = SpringMvcServerMain.startServer(port);
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (store != null) {
            store.close();
        }
        if (tomcat != null) {
            tomcat.stop();
        }
    }

    @Test(timeout = 120_000)
    public void fullRoundTripOnRealHost() throws Exception {
        // 1. 真实客户端（Spring 装配同款路径 + 临时 SQLite）
        Path db = Files.createTempDirectory("linkora-smoke").resolve("client.db");
        store = new SqliteClientStore(db);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port)
                .clientId(CLIENT_ID)
                .clientStore(store)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        client.addListener("order-created", listener);
        client.start();

        // 2. 下行：走真实业务端点发布（订单号作顺序键）→ 真实 SSE → 客户端监听器收到
        int code = post("/api/orders?clientId=" + CLIENT_ID + "&orderNo=SMOKE-1&item=down");
        assertEquals(200, code);
        assertTrue("下行消息应在超时内送达", listener.awaitCount(1, 20_000));
        assertEquals("SMOKE-1", listener.messages().get(0).getPayloadAsUtf8String()
                .replaceAll(".*\"orderNo\":\"([^\"]+)\".*", "$1"));

        // 3. 上行：客户端发布 → 服务端接受并入库
        String uplinkId = client.publish("smoke-report", "up".getBytes(StandardCharsets.UTF_8));
        assertTrue("上行应被服务端确认接受", awaitOutboxAccepted(uplinkId, 20_000));
        assertTrue("服务端应已收到上行消息", awaitServerReceived("smoke-report", 20_000));
    }

    private int post(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(new byte[0]);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private boolean awaitOutboxAccepted(String messageId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (store.findOutbox(messageId).map(e -> e.getStatus() == OutboxStatus.SERVER_ACCEPTED)
                    .orElse(false)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean awaitServerReceived(String messageType, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                    + "/api/admin/messages?clientId=" + CLIENT_ID + "&messageType=" + messageType)
                    .openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            if (body.contains("smoke-report")) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
