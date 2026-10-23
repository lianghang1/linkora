package com.linkora.testkit;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.message.MessageState;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.OutboxEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * testkit 自证测试：嵌入式服务器 × 真实客户端做一次完整上行/下行/ACK 往返，
 * 证明 testkit 提供的环境与生产链路语义一致。
 *
 * @author lianghang
 */
public class EmbeddedLinkoraServerTest {

    private static final String CLIENT_ID = "tk-client";
    private static final String TOPIC = "greeting";

    private EmbeddedLinkoraServer testServer;
    private SqliteClientStore clientStore;
    private LinkoraClient client;

    @Before
    public void setUp() throws Exception {
        testServer = EmbeddedLinkoraServer.builder()
                .registerClient(CLIENT_ID)
                .build();
        testServer.start();
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
        if (flakyTransport != null) {
            flakyTransport.close();
        }
        if (clientStore != null) {
            clientStore.close();
        }
        testServer.close();
    }

    @Test
    public void roundTripWithRealClient() throws Exception {
        Path dbFile = Files.createTempDirectory("linkora-testkit").resolve("client.db");
        clientStore = new SqliteClientStore(dbFile);
        client = LinkoraClient.builder()
                .serverUrl(testServer.baseUrl())
                .clientId(CLIENT_ID)
                .clientStore(clientStore)
                .config(ClientConfig.builder()
                        .senderIntervalMs(100)
                        .recoveryIntervalMs(300)
                        .build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        client.addListener(TOPIC, listener);
        client.start();

        // 上行：SQLite outbox → POST → 服务端幂等接受
        String uplinkId = client.publish("demo-report", "ping".getBytes(StandardCharsets.UTF_8));
        assertTrue(awaitOutboxStatus(clientStore, uplinkId, OutboxStatus.SERVER_ACCEPTED, 10000));

        // 下行：服务端 publish → SSE 推送 → 客户端监听器收到并自动 PROCESSED
        testServer.publish(CLIENT_ID, TOPIC, "hello-testkit".getBytes(StandardCharsets.UTF_8));
        assertTrue("应在超时内收到下行消息", listener.awaitCount(1, 10000));
        List<LinkoraReceivedMessage> messages = listener.messages();
        assertEquals(1, messages.size());
        assertEquals("hello-testkit", messages.get(0).getPayloadAsUtf8String());

        String downlinkId = messages.get(0).getMessageId();
        assertTrue(awaitMessageState(testServer, downlinkId, MessageState.PROCESSED, 10000));
    }

    @Test
    public void disconnectClientKillsConnectionAndClientRecovers() throws Exception {
        // 管理 API 真实断连演示：踢掉假在线连接 → 客户端自动重连 → 后续消息照常收到
        Path dbFile = Files.createTempDirectory("linkora-testkit").resolve("client.db");
        clientStore = new SqliteClientStore(dbFile);
        client = LinkoraClient.builder()
                .serverUrl(testServer.baseUrl())
                .clientId(CLIENT_ID)
                .clientStore(clientStore)
                .config(ClientConfig.builder()
                        .senderIntervalMs(100)
                        .recoveryIntervalMs(300)
                        .build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        client.addListener(TOPIC, listener);
        client.start();

        // 等待真实 SSE 连接建立
        assertTrue("客户端应在线", awaitOnline(testServer, 15000));

        // 管理 API 踢连接：必须真实断开至少一条
        int closed = testServer.server().disconnectClient(CLIENT_ID);
        assertTrue("应至少断开一条真实 SSE 连接", closed >= 1);
        assertTrue("踢完应离线", awaitOffline(testServer, 5000));

        // 客户端自动重连（指数退避）后，后续消息照常收到
        assertTrue("客户端应自动重连上线", awaitOnline(testServer, 20000));
        testServer.publish(CLIENT_ID, TOPIC, "after-disconnect".getBytes(StandardCharsets.UTF_8));
        assertTrue("重连后应照常收到消息", listener.awaitCount(1, 15000));
        assertEquals("after-disconnect", listener.messages().get(0).getPayloadAsUtf8String());
    }

    @Test
    public void remoteCommandRetriesClientOutboxDeadLetter() throws Exception {
        // 全链路演示：上行因传输故障进死信 → 服务端远程下发重发指令 → 客户端执行 → 服务端收到
        Path dbFile = Files.createTempDirectory("linkora-testkit").resolve("client.db");
        clientStore = new SqliteClientStore(dbFile);
        // 前 1 次发送注入"不可重试失败"（模拟认证配置错误等场景），之后恢复正常
        com.linkora.transport.http.OkHttpTransport real =
                com.linkora.transport.http.OkHttpTransport.builder().build();
        java.util.concurrent.atomic.AtomicInteger failuresLeft =
                new java.util.concurrent.atomic.AtomicInteger(1);
        flakyTransport = new com.linkora.transport.http.HttpTransport() {
            @Override
            public com.linkora.transport.http.TransportResponse execute(
                    com.linkora.transport.http.TransportRequest request)
                    throws com.linkora.transport.http.TransportException {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw new com.linkora.transport.http.TransportException(
                            com.linkora.transport.http.TransportErrorType.CLIENT_ERROR,
                            "模拟不可重试故障", null, 400, null);
                }
                return real.execute(request);
            }

            @Override
            public void close() {
                real.close();
            }
        };
        client = LinkoraClient.builder()
                .serverUrl(testServer.baseUrl())
                .clientId(CLIENT_ID)
                .clientStore(clientStore)
                .transport(flakyTransport)
                .config(ClientConfig.builder()
                        .senderIntervalMs(100)
                        .recoveryIntervalMs(300)
                        .build())
                .build();
        client.start();
        assertTrue("客户端应在线", awaitOnline(testServer, 15000));

        // 1. 上行发送遭遇不可重试故障 → 立即进死信
        String uplinkId = client.publish("demo-report", "上报数据".getBytes(StandardCharsets.UTF_8));
        assertTrue("应进入上行死信", awaitOutboxStatus(clientStore, uplinkId,
                OutboxStatus.DEAD_LETTER, 10000));
        assertEquals(1, client.listOutboxDeadLetters(10).size());

        // 2. 死信不会自动复活——等待验证
        Thread.sleep(800);
        assertTrue("死信必须仍在（不会自动复活）",
                awaitOutboxStatus(clientStore, uplinkId, OutboxStatus.DEAD_LETTER, 800));

        // 3. 服务端远程下发重发指令（ctrl 事件，实时送达在线设备）
        int delivered = testServer.server().retryClientOutboxDeadLetter(CLIENT_ID, uplinkId);
        assertTrue("指令应送达在线设备", delivered >= 1);

        // 4. 客户端执行重发（故障已消失）→ 服务端接受 → 死信清空
        assertTrue("重发后应被服务端接受", awaitOutboxStatus(clientStore, uplinkId,
                OutboxStatus.SERVER_ACCEPTED, 10000));
        assertEquals(0, client.listOutboxDeadLetters(10).size());
    }

    private com.linkora.transport.http.HttpTransport flakyTransport;

    @Test
    public void remoteQueryReturnsClientLocalRows() throws Exception {
        // 远程查询：服务端下发 QUERY_OUTBOX → 客户端执行 → 结果经上行回传 → getCommandResult 可读
        Path dbFile = Files.createTempDirectory("linkora-testkit").resolve("client.db");
        clientStore = new SqliteClientStore(dbFile);
        client = LinkoraClient.builder()
                .serverUrl(testServer.baseUrl())
                .clientId(CLIENT_ID)
                .clientStore(clientStore)
                .config(ClientConfig.builder()
                        .senderIntervalMs(100)
                        .recoveryIntervalMs(300)
                        .build())
                .build();
        client.start();
        assertTrue("客户端应在线", awaitOnline(testServer, 15000));

        // 本地先造两条 outbox 记录（一条已确认、一条待发）
        client.publish("report-1", "r1".getBytes(StandardCharsets.UTF_8));
        String pending = client.publish("report-2", "r2".getBytes(StandardCharsets.UTF_8));

        // 远程查询
        String queryId = testServer.server().queryClientOutbox(CLIENT_ID, null, 50);
        assertNotNull("指令应已送达（客户端在线）", queryId);

        // 结果经上行回传（queryId 关联）
        long deadline = System.currentTimeMillis() + 15000;
        String result = null;
        while (System.currentTimeMillis() < deadline) {
            result = testServer.server().getCommandResult(queryId);
            if (result != null) {
                break;
            }
            Thread.sleep(100);
        }
        assertNotNull("应在超时内收到远程查询结果", result);
        assertTrue("结果必须包含查询标识", result.contains(queryId));
        assertTrue("结果必须包含本地行数据", result.contains(pending));
    }

    private static boolean awaitOnline(EmbeddedLinkoraServer server, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (server.server().getBacklog(CLIENT_ID).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static boolean awaitOffline(EmbeddedLinkoraServer server, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!server.server().getBacklog(CLIENT_ID).isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static boolean awaitOutboxStatus(SqliteClientStore store, String messageId,
                                             OutboxStatus expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        OutboxStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            OutboxEntry entry = store.findOutbox(messageId).orElse(null);
            status = entry == null ? null : entry.getStatus();
            if (status == expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return status == expected;
    }

    private static boolean awaitMessageState(EmbeddedLinkoraServer server, String messageId,
                                             MessageState expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        MessageState state = null;
        while (System.currentTimeMillis() < deadline) {
            com.linkora.server.MessageDeliveryStatus status =
                    server.server().getMessageStatus(messageId).orElse(null);
            state = status == null ? null : status.getDeliveryRecords().get(0).getState();
            if (state == expected) {
                return true;
            }
            Thread.sleep(50);
        }
        return state == expected;
    }
}
