package com.linkora.example.boot;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.message.OutboxStatus;
import com.linkora.server.LinkoraServer;
import com.linkora.testkit.RecordingMessageListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 宿主装配烟测（Boot / Tomcat）：编程式启动真实 Boot 宿主（starter 完整自动装配），
 * 与真实客户端经真实 SSE + HTTP 完成下行与上行往返。
 *
 * @author lianghang
 */
class BootHostSmokeIT {

    private static final String CLIENT_ID = "smoke-client";

    private ConfigurableApplicationContext context;
    private LinkoraClient client;
    private SqliteClientStore store;

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (store != null) {
            store.close();
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void fullRoundTripOnRealHost() throws Exception {
        // 1. 启动真实 Boot 宿主（starter 自动装配 LinkoraServer + 内嵌 Tomcat，随机端口）
        context = new SpringApplicationBuilder(SpringBootServerApplication.class)
                .properties("server.port=0",
                        "linkora.server.register-clients." + CLIENT_ID + "=true")
                .run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        LinkoraServer linkoraServer = context.getBean(LinkoraServer.class);

        // 2. 真实客户端（同款装配语义 + 临时 SQLite）；token 认证（0.6.0），
        //    与宿主 HostTokenValidator 演示约定一致：grp-<clientId>-<日期>
        Path db = Files.createTempDirectory("linkora-smoke").resolve("client.db");
        store = new SqliteClientStore(db);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port)
                .clientId(CLIENT_ID)
                .clientStore(store)
                .credentialProvider(com.linkora.client.auth.TokenCredentialProvider
                        .forStaticToken("grp-" + CLIENT_ID + "-2026"))
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300)
        .payloadCipherKey("Qr1J5BCqFYkIlxfELSx6xYoZZ5YxYEZyWHZrBOJT83M=").build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        client.addListener("smoke-msg", listener);
        client.start();
        assertTrue(awaitOnline(linkoraServer, 20_000), "客户端应经真实 SSE 上线");

        // 3. 下行：服务端发布 → 真实 SSE 推送 → 客户端监听器收到
        linkoraServer.publish(CLIENT_ID, "smoke-msg", "down".getBytes(StandardCharsets.UTF_8));
        assertTrue(listener.awaitCount(1, 15_000), "下行消息应在超时内送达");
        List<LinkoraReceivedMessage> messages = listener.messages();
        assertEquals("down", messages.get(0).getPayloadAsUtf8String());

        // 4. 上行：客户端发布 → 服务端接受并入库
        String uplinkId = client.publish("smoke-report", "up".getBytes(StandardCharsets.UTF_8));
        assertTrue(awaitOutboxAccepted(uplinkId, 15_000), "上行应被服务端确认接受");
        assertTrue(awaitServerReceived(linkoraServer, "smoke-report", 15_000), "服务端应已收到上行消息");
    }

    @Test
    void adminConsoleServesPageAndEndpointsWithTokenAuth() throws Exception {
        // 启动真实 Boot 宿主，显式开启控制台（starter 自动装配 + Bearer Token）
        context = new SpringApplicationBuilder(SpringBootServerApplication.class)
                .properties("server.port=0",
                        "linkora.server.register-clients." + CLIENT_ID + "=true",
                        "linkora.admin-console.enabled=true",
                        "linkora.admin-console.token=it-console-token")
                .run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();

        // 无令牌 / 错误令牌 → 401
        assertEquals(401, httpStatus(port, "/linkora-admin/api/overview", null));
        assertEquals(401, httpStatus(port, "/linkora-admin/api/overview", "wrong-token"));

        // 正确令牌：页面与端点可用
        String page = httpGet(port, "/linkora-admin/", "it-console-token");
        assertTrue(page.contains("Linkora 运维控制台"), "控制台单页应正常直出");
        String overview = httpGet(port, "/linkora-admin/api/overview", "it-console-token");
        assertTrue(overview.contains("smoke-client"), "总览应包含注册客户端");

        // 写操作端点 + reason 留痕（断开连接：客户端离线返回 count=0 也算端点生效）
        String disconnect = httpPost(port, "/linkora-admin/api/actions/disconnect",
                "it-console-token", "{\"clientId\":\"smoke-client\",\"reason\":\"烟测\"}");
        assertTrue(disconnect.contains("\"count\""), "写操作端点应返回执行结果");
    }

    /** 控制台远程指令全链路：真实 SSE ctrl 下发 → 真实客户端执行 QUERY_OUTBOX → 上行回传结果。 */
    @Test
    void adminConsoleQueriesClientOutboxOverRealChannel() throws Exception {
        context = new SpringApplicationBuilder(SpringBootServerApplication.class)
                .properties("server.port=0",
                        "linkora.server.register-clients." + CLIENT_ID + "=true",
                        "linkora.admin-console.enabled=true",
                        "linkora.admin-console.token=it-console-token")
                .run();
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        LinkoraServer linkoraServer = context.getBean(LinkoraServer.class);

        // 真实客户端上线
        Path db = Files.createTempDirectory("linkora-obx").resolve("client.db");
        store = new SqliteClientStore(db);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port)
                .clientId(CLIENT_ID)
                .clientStore(store)
                .credentialProvider(com.linkora.client.auth.TokenCredentialProvider
                        .forStaticToken("grp-" + CLIENT_ID + "-2026"))
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300)
        .payloadCipherKey("Qr1J5BCqFYkIlxfELSx6xYoZZ5YxYEZyWHZrBOJT83M=").build())
                .build();
        client.start();
        assertTrue(awaitOnline(linkoraServer, 20_000), "客户端应经真实 SSE 上线");

        // 客户端产生本地 outbox 数据并被服务端接受
        String uplinkId = client.publish("obx-demo", "outbox-row".getBytes(StandardCharsets.UTF_8));
        assertTrue(awaitOutboxAccepted(uplinkId, 15_000), "上行应被服务端确认接受");

        // 控制台下发 QUERY_OUTBOX（真实 ctrl 通道）
        String query = httpPost(port, "/linkora-admin/api/actions/client-command",
                "it-console-token",
                "{\"clientId\":\"" + CLIENT_ID + "\",\"command\":\"QUERY_OUTBOX\",\"reason\":\"真机链路验证\"}");
        assertTrue(query.contains("queryId"), "应返回 queryId");
        String queryId = query.replaceAll(".*\"queryId\":\"([^\"]+)\".*", "$1");

        // 轮询回执：客户端执行后经上行链路回传
        String result = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String r = httpGet(port, "/linkora-admin/api/command-result/" + queryId,
                    "it-console-token");
            if (r.contains("rows") && !r.contains("\"rows\":[]")) {
                result = r;
                break;
            }
            Thread.sleep(300);
        }
        assertTrue(result != null, "应在超时内收到客户端回执");
        assertTrue(result.contains(uplinkId), "回执应包含本地 outbox 条目 " + uplinkId);
    }

    private int httpStatus(int port, String path, String token) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + port + path).openConnection();
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        int status = conn.getResponseCode();
        conn.disconnect();
        return status;
    }

    private String httpGet(int port, String path, String token) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
        conn.disconnect();
        return body;
    }

    private String httpPost(int port, String path, String token, String json) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setDoOutput(true);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
        conn.disconnect();
        return body;
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private boolean awaitOnline(LinkoraServer server, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server.getBacklog(CLIENT_ID).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
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

    private boolean awaitServerReceived(LinkoraServer server, String messageType, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!server.listMessages(CLIENT_ID, messageType, null, null, 1, 10).isEmpty()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
