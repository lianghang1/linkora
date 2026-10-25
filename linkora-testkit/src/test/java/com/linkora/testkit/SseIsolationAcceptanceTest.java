package com.linkora.testkit;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.message.MessageState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验收测试（docs/design/sse-async-write.md §6）：非阻塞写模型的隔离性。
 *
 * <p>场景：慢客户端（订阅后不读数据）被大消息灌满出站队列 → 服务端必须
 * ① 不停摆（正常客户端接收延迟秒级）② 按水位主动断开慢客户端 ③ 慢客户端
 * 重连后消息补发完整。修复前（同步写模型）Tomcat 下正常客户端永远收不到。</p>
 *
 * @author lianghang
 */
public class SseIsolationAcceptanceTest {

    private static final String STUCK_ID = "stuck-store";
    private static final String NORMAL_ID = "normal-store";
    private static final String TOPIC = "greeting";
    /** 1MB payload → base64 帧 ~1.37MB；8MB 水位需 ~6 条。 */
    private static final int FLOOD_COUNT = 8;

    private EmbeddedLinkoraServer testServer;
    private SqliteClientStore normalStore;
    private LinkoraClient normalClient;
    private Socket stuckSocket;

    @Before
    public void setUp() throws Exception {
        testServer = EmbeddedLinkoraServer.builder()
                .registerClient(STUCK_ID)
                .registerClient(NORMAL_ID)
                .build();
        testServer.start();
    }

    @After
    public void tearDown() throws Exception {
        if (stuckSocket != null && !stuckSocket.isClosed()) {
            stuckSocket.close();
        }
        if (normalClient != null) {
            normalClient.close();
        }
        if (normalStore != null) {
            normalStore.close();
        }
        testServer.close();
    }

    @Test(timeout = 120_000)
    public void slowClientMustNotStallOthersAndBackfillOnReconnect() throws Exception {
        // 1. 正常客户端（真实 SSE）+ 慢客户端（裸 socket 订阅后停止读取）
        Path db = Files.createTempDirectory("linkora-accept").resolve("normal.db");
        normalStore = new SqliteClientStore(db);
        normalClient = LinkoraClient.builder()
                .serverUrl(testServer.baseUrl())
                .clientId(NORMAL_ID)
                .clientStore(normalStore)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        normalClient.addListener(TOPIC, listener);
        normalClient.start();
        assertTrue("正常客户端应在线", awaitOnline(NORMAL_ID, 15_000));

        stuckSocket = new Socket("127.0.0.1", testServer.port());
        stuckSocket.setReceiveBufferSize(8 * 1024);
        OutputStream out = stuckSocket.getOutputStream();
        out.write(("GET /events?clientId=" + STUCK_ID + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        stuckSocket.getInputStream().read(new byte[2048]); // 只读响应头，之后永久停止读取
        assertTrue("慢客户端应已在服务端注册", awaitOnline(STUCK_ID, 10_000));

        // 2. 大消息灌满慢客户端出站队列（超过 8MB 水位 → 服务端主动断开它）
        byte[] big = new byte[1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'A');
        for (int i = 0; i < FLOOD_COUNT; i++) {
            testServer.publish(STUCK_ID, TOPIC, big);
        }
        assertTrue("慢客户端应被水位策略主动断开", awaitOffline(STUCK_ID, 15_000));

        // 3. 隔离性（核心断言）：灌满期间正常客户端的接收必须秒级
        long probeStart = System.currentTimeMillis();
        testServer.publish(NORMAL_ID, TOPIC, "probe-during-flood".getBytes(StandardCharsets.UTF_8));
        assertTrue("隔离性：正常客户端必须在慢客户端被灌满期间正常收到消息",
                listener.awaitCount(1, 5_000));
        long latency = System.currentTimeMillis() - probeStart;
        assertTrue("正常客户端接收延迟必须秒级（实测 " + latency + "ms）", latency < 3_000);
        System.out.println("[acceptance] 正常客户端在洪水期间接收延迟: " + latency + "ms");

        // 4. 补发完整：慢客户端重连（真实客户端）后应收到被积压的大消息
        Path stuckDb = Files.createTempDirectory("linkora-accept").resolve("stuck.db");
        SqliteClientStore stuckStore = new SqliteClientStore(stuckDb);
        LinkoraClient stuckClient = null;
        try {
            stuckClient = LinkoraClient.builder()
                    .serverUrl(testServer.baseUrl())
                    .clientId(STUCK_ID)
                    .clientStore(stuckStore)
                    .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                    .build();
            RecordingMessageListener stuckListener = new RecordingMessageListener();
            stuckClient.addListener(TOPIC, stuckListener);
            stuckClient.start();
            assertTrue("重连的慢客户端应通过补发收到积压消息",
                    stuckListener.awaitCount(1, 30_000));
            assertEquals(1024 * 1024, stuckListener.messages().get(0).getPayload().length);
        } finally {
            if (stuckClient != null) {
                stuckClient.close();
            }
            stuckStore.close();
        }
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }

    private boolean awaitOnline(String clientId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (testServer.server().getBacklog(clientId).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean awaitOffline(String clientId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!testServer.server().getBacklog(clientId).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
