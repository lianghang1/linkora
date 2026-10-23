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
