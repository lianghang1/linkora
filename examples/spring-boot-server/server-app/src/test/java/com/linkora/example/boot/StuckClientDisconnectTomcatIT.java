package com.linkora.example.boot;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.server.LinkoraServer;
import com.linkora.testkit.RecordingMessageListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收测试 IT（Tomcat 容器，docs/design/sse-async-write.md §6）：
 * 慢客户端（订阅后不读数据）被大消息灌满出站队列后——
 * ① 服务端不停摆（正常客户端接收秒级，修复前：永远收不到）
 * ② 水位策略主动断开慢客户端 ③ 重连后消息补发完整。
 * 基线数据：修复前本场景在 Tomcat 下写阻塞无任何自愈手段（实测 120s+ 不恢复）。
 *
 * @author lianghang
 */
@SpringBootTest(classes = SpringBootServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "linkora.server.register-clients.stuck-store=true",
                "linkora.server.register-clients.normal-store=true"
        })
class StuckClientDisconnectTomcatIT {

    private static final int FLOOD_COUNT = 8;

    @LocalServerPort
    private int port;

    @Autowired
    private LinkoraServer linkoraServer;

    private Socket stuckSocket;
    private LinkoraClient normalClient;
    private SqliteClientStore normalStore;

    @AfterEach
    void tearDown() throws Exception {
        if (stuckSocket != null && !stuckSocket.isClosed()) {
            stuckSocket.close();
        }
        if (normalClient != null) {
            normalClient.close();
        }
        if (normalStore != null) {
            normalStore.close();
        }
    }

    @Test
    void slowClientMustNotStallOthers() throws Exception {
        // 1. 正常客户端（真实 SSE + SQLite）
        Path db = Files.createTempDirectory("linkora-it").resolve("normal.db");
        normalStore = new SqliteClientStore(db);
        normalClient = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port)
                .clientId("normal-store")
                .clientStore(normalStore)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        normalClient.addListener("greeting", listener);
        normalClient.start();
        boolean normalOnline = awaitOnline("normal-store", 20_000);
        assertTrue(normalOnline, "正常客户端应在线");

        // 2. 慢客户端：裸 socket 订阅后永久停止读取
        stuckSocket = new Socket("127.0.0.1", port);
        stuckSocket.setReceiveBufferSize(8 * 1024);
        OutputStream out = stuckSocket.getOutputStream();
        out.write(("GET /events?clientId=stuck-store HTTP/1.1\r\nHost: localhost\r\n"
                + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = stuckSocket.getInputStream();
        byte[] head = new byte[2048];
        int n = in.read(head);
        String headStr = new String(head, 0, Math.max(0, n), StandardCharsets.UTF_8);
        assertFalse(headStr.contains("404"), "订阅应成功: " + headStr.lines().findFirst().orElse(""));
        boolean stuckOnline = awaitOnline("stuck-store", 15_000);
        assertTrue(stuckOnline, "慢客户端应已在服务端注册");

        // 3. 大消息灌满出站队列 → 水位策略应主动断开慢客户端
        byte[] big = new byte[1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'A');
        for (int i = 0; i < FLOOD_COUNT; i++) {
            linkoraServer.publish("stuck-store", "greeting", big);
        }
        boolean disconnected = awaitOffline("stuck-store", 20_000);
        assertTrue(disconnected, "慢客户端应被水位策略主动断开");

        // 4. 隔离性（核心断言）：洪水期间正常客户端接收必须秒级
        long probeStart = System.currentTimeMillis();
        linkoraServer.publish("normal-store", "greeting", "probe".getBytes(StandardCharsets.UTF_8));
        boolean received = listener.awaitCount(1, 10_000);
        assertTrue(received, "隔离性：正常客户端必须正常收到消息");
        long latency = System.currentTimeMillis() - probeStart;
        assertTrue(latency < 5_000, "正常客户端接收延迟必须秒级（实测 " + latency + "ms）");
        System.out.println("[tomcat-it] 正常客户端在洪水期间接收延迟: " + latency + "ms");

        // 5. 补发完整：慢客户端重连后应收到积压的大消息
        Path stuckDb = Files.createTempDirectory("linkora-it").resolve("stuck.db");
        SqliteClientStore stuckStore = new SqliteClientStore(stuckDb);
        LinkoraClient stuckClient = null;
        try {
            stuckClient = LinkoraClient.builder()
                    .serverUrl("http://127.0.0.1:" + port)
                    .clientId("stuck-store")
                    .clientStore(stuckStore)
                    .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                    .build();
            RecordingMessageListener stuckListener = new RecordingMessageListener();
            stuckClient.addListener("greeting", stuckListener);
            stuckClient.start();
            boolean backfilled = stuckListener.awaitCount(1, 30_000);
            assertTrue(backfilled, "重连的慢客户端应通过补发收到积压消息");
            assertEquals(1024 * 1024, stuckListener.messages().get(0).getPayload().length);
        } finally {
            if (stuckClient != null) {
                stuckClient.close();
            }
            stuckStore.close();
        }
    }

    private boolean awaitOnline(String clientId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (linkoraServer.getBacklog(clientId).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean awaitOffline(String clientId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!linkoraServer.getBacklog(clientId).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
