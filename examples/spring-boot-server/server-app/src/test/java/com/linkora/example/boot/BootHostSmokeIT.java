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

        // 2. 真实客户端（同款装配语义 + 临时 SQLite）
        Path db = Files.createTempDirectory("linkora-smoke").resolve("client.db");
        store = new SqliteClientStore(db);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port)
                .clientId(CLIENT_ID)
                .clientStore(store)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
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
