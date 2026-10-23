package com.linkora.testkit;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.storage.sqlite.SqliteClientStore;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Spike：验证"假在线"客户端（TCP 在但不读数据）塞满服务端缓冲区导致写阻塞后，
 * 管理 API disconnectClient 能否立即解卡、其他客户端是否全程无感。
 *
 * 场景：裸 socket 建立订阅后停止读取 → 大消息灌满缓冲区（秒级）→ 编排线程卡在 flush
 * → 踢连接 → 计时观察编排线程是否恢复。
 *
 * @author lianghang
 */
public class StuckClientDisconnectSpikeTest {

    private static final String STUCK_ID = "stuck-store";
    private static final String NORMAL_ID = "normal-store";
    private static final String TOPIC = "greeting";

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
    public void disconnectUnblocksStuckClientWrite() throws Exception {
        RecordingListener normalListener = startNormalClient();

        // 1. 假客户端：裸 socket 订阅后停止读取（小接收缓冲，加速填满）
        stuckSocket = new Socket("127.0.0.1", testServer.port());
        stuckSocket.setSoTimeout(0);
        stuckSocket.setReceiveBufferSize(8 * 1024);
        OutputStream out = stuckSocket.getOutputStream();
        out.write(("GET /events?clientId=" + STUCK_ID + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        // 只读响应头和初始帧，之后永久停止读取
        InputStream in = stuckSocket.getInputStream();
        byte[] head = new byte[2048];
        in.read(head);
        assertFalse("服务端应认可订阅（HTTP 200）",
                new String(head, StandardCharsets.UTF_8).contains("404"));
        assertTrue("假客户端应已在服务端注册",
                awaitOnline(testServer, STUCK_ID, 10_000));

        // 2. 大消息灌缓冲区：1MB payload（base64 后 ~1.37MB/帧）
        byte[] big = new byte[1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'A');
        long fillStart = System.currentTimeMillis();
        testServer.publish(STUCK_ID, TOPIC, big);
        // 第二发确保缓冲区彻底塞满（即便首帧部分写入）
        testServer.publish(STUCK_ID, TOPIC, big);
        System.out.println("[spike] 大消息已发出，耗时 "
                + (System.currentTimeMillis() - fillStart) + "ms（异步提交）");

        // 3. 等待编排线程卡死：给正常门店发小消息，若秒级收不到即认定卡住
        Thread.sleep(2000);
        long probeStart = System.currentTimeMillis();
        testServer.publish(NORMAL_ID, TOPIC, "probe-while-stuck".getBytes(StandardCharsets.UTF_8));
        boolean blocked = !normalListener.awaitPayload("probe-while-stuck", 4000);
        long stuckConfirmMs = System.currentTimeMillis() - probeStart;
        System.out.println("[spike] 卡住判定: " + (blocked ? "已卡住（正常门店 4s 未收到探测消息）"
                : "未卡住（缓冲区没填满，需要加大 payload/减小 SO_RCVBUF）"));
        System.out.println("[spike] 探测消息实际送达耗时: " + stuckConfirmMs + "ms");

        if (blocked) {
            // 4. 踢掉假客户端并计时：编排线程是否立即解卡
            long kickStart = System.currentTimeMillis();
            int closed = testServer.server().disconnectClient(STUCK_ID);
            System.out.println("[spike] disconnectClient 返回: " + closed
                    + "，开始计时等待正常门店恢复接收…");
            boolean recovered = normalListener.awaitPayload("probe-while-stuck", 30_000);
            long unblockMs = System.currentTimeMillis() - kickStart;
            System.out.println("[spike] ===== 结论 =====");
            System.out.println("[spike] 踢连接后解卡耗时: " + unblockMs + "ms"
                    + (recovered ? "（已解卡 ✅）" : "（30s 未解卡 ❌，只能等 TCP 超时）"));
            assertTrue("踢连接应能解卡（正常门店恢复收到探测消息）", recovered);
            assertTrue("解卡应在秒级完成（而非等 TCP 超时）", unblockMs < 10_000);

            // 5. 收尾验证：解卡后服务端能继续正常服务新消息
            testServer.publish(NORMAL_ID, TOPIC, "after-unblock".getBytes(StandardCharsets.UTF_8));
            assertTrue("解卡后新消息应正常送达",
                    normalListener.awaitPayload("after-unblock", 10_000));
        }
    }

    /**
     * 对照组：卡住后不踢连接，观察容器自愈时间。
     * 用于区分"close() 解卡"与"容器异步超时自愈"。
     */
    @Test(timeout = 120_000)
    public void stuckWithoutKickAutoRecoveryTiming() throws Exception {
        RecordingListener normalListener = startNormalClient();
        stuckSocket = new Socket("127.0.0.1", testServer.port());
        stuckSocket.setReceiveBufferSize(8 * 1024);
        OutputStream out = stuckSocket.getOutputStream();
        out.write(("GET /events?clientId=" + STUCK_ID + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        stuckSocket.getInputStream().read(new byte[2048]);
        assertTrue(awaitOnline(testServer, STUCK_ID, 10_000));

        byte[] big = new byte[1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'A');
        testServer.publish(STUCK_ID, TOPIC, big);
        testServer.publish(STUCK_ID, TOPIC, big);
        Thread.sleep(2000);
        long probeStart = System.currentTimeMillis();
        testServer.publish(NORMAL_ID, TOPIC, "probe-nokick".getBytes(StandardCharsets.UTF_8));
        boolean blocked = !normalListener.awaitPayload("probe-nokick", 4000);
        System.out.println("[spike-ctrl] 卡住判定: " + (blocked ? "已卡住" : "未卡住"));
        if (!blocked) {
            return; // 环境未复现，跳过
        }
        long t0 = System.currentTimeMillis();
        boolean recovered = normalListener.awaitPayload("probe-nokick", 90_000);
        System.out.println("[spike-ctrl] ===== 对照组结论 =====");
        System.out.println("[spike-ctrl] 不踢连接的自愈耗时: "
                + (System.currentTimeMillis() - t0) + "ms"
                + (recovered ? "" : "（90s 仍未自愈）"));
    }

    private RecordingListener startNormalClient() throws Exception {
        Path db = Files.createTempDirectory("linkora-spike").resolve("normal.db");
        normalStore = new SqliteClientStore(db);
        normalClient = LinkoraClient.builder()
                .serverUrl(testServer.baseUrl())
                .clientId(NORMAL_ID)
                .clientStore(normalStore)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        RecordingListener listener = new RecordingListener();
        normalClient.addListener(TOPIC, listener);
        normalClient.start();
        return listener;
    }

    private static boolean awaitOnline(EmbeddedLinkoraServer server, String clientId, long timeoutMs)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server.server().getBacklog(clientId).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** 记录收到 payload 的监听器（含等待辅助）。 */
    static final class RecordingListener implements com.linkora.client.MessageListener {
        private final List<String> payloads = new CopyOnWriteArrayList<String>();

        @Override
        public void onMessage(LinkoraReceivedMessage message) {
            payloads.add(message.getPayloadAsUtf8String());
        }

        boolean awaitPayload(String expected, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                for (String p : payloads) {
                    if (p.contains(expected)) {
                        return true;
                    }
                }
                Thread.sleep(50);
            }
            return false;
        }
    }
}
