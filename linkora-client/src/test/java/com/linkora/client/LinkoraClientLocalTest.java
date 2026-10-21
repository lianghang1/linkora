package com.linkora.client;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 无服务端场景：入队、崩溃恢复、生命周期。
 *
 * @author lianghang
 */
public class LinkoraClientLocalTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private LinkoraClient newClient(ClientStore store) {
        return LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:1") // 不可达地址：本测试不发起真实请求
                .clientId("store-001")
                .clientStore(store)
                .build();
    }

    @Test
    public void publishEnqueuesPendingOutbox() {
        SqliteClientStore store = new SqliteClientStore(
                new java.io.File(tmp.getRoot(), "client.db").toPath());
        try {
            LinkoraClient client = newClient(store);
            try {
                String messageId = client.publish("STOCK", "{\"qty\":1}".getBytes(StandardCharsets.UTF_8));
                OutboxEntry entry = store.findOutbox(messageId).orElse(null);
                assertNotNull(entry);
                assertEquals(OutboxStatus.PENDING, entry.getStatus());
                assertEquals("STOCK", entry.getMessageType());
            } finally {
                client.close();
            }
            assertEquals(ClientState.CLOSED, client.getState());
        } finally {
            store.close();
        }
    }

    @Test
    public void startIsIdempotentAndClosePreventsPublish() {
        SqliteClientStore store = new SqliteClientStore(
                new java.io.File(tmp.getRoot(), "client2.db").toPath());
        try {
            LinkoraClient client = newClient(store);
            try {
                client.start();
                client.start(); // 幂等
                assertEquals(ClientState.RUNNING, client.getState());
                client.close();
                client.close(); // 幂等
                assertEquals(ClientState.CLOSED, client.getState());
                boolean threw = false;
                try {
                    client.publish("T", "p".getBytes(StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    threw = true;
                }
                assertTrue("关闭后 publish 应抛出异常", threw);
            } finally {
                client.close();
            }
        } finally {
            store.close();
        }
    }

    @Test
    public void objectPayloadRequiresSerializer() {
        SqliteClientStore store = new SqliteClientStore(
                new java.io.File(tmp.getRoot(), "client3.db").toPath());
        try {
            LinkoraClient without = newClient(store);
            try {
                boolean threw = false;
                try {
                    without.publish("T", new Object());
                } catch (RuntimeException e) {
                    threw = true;
                }
                assertTrue(threw);
            } finally {
                without.close();
            }

            LinkoraClient with = LinkoraClient.builder()
                    .serverUrl("http://127.0.0.1:1")
                    .clientId("store-001")
                    .clientStore(store)
                    .payloadSerializer(new com.linkora.serialize.jackson.JacksonPayloadSerializer())
                    .build();
            try {
                Payload pojo = new Payload();
                pojo.qty = 5;
                String messageId = with.publish("T", pojo);
                OutboxEntry entry = withStore(store, messageId);
                assertNotNull(entry);
                assertTrue(new String(entry.getPayload(), StandardCharsets.UTF_8).contains("\"qty\":5"));
            } finally {
                with.close();
            }
        } finally {
            store.close();
        }
    }

    private OutboxEntry withStore(ClientStore store, String messageId) {
        return store.findOutbox(messageId).orElse(null);
    }

    @Test
    public void recoveryResetsStuckSendingEntries() throws Exception {
        SqliteClientStore store = new SqliteClientStore(
                new java.io.File(tmp.getRoot(), "client4.db").toPath());
        try {
            long now = System.currentTimeMillis();
            // 模拟崩溃残留的 SENDING 记录
            store.saveOutbox(new OutboxEntry("stuck-1", "T", "p".getBytes(StandardCharsets.UTF_8),
                    OutboxStatus.SENDING, now, now, 0, null, null, null));
            assertFalse(store.findOutbox("stuck-1").orElse(null).getStatus() == OutboxStatus.PENDING);

            LinkoraClient client = LinkoraClient.builder()
                    .serverUrl("http://127.0.0.1:1") // 不可达地址
                    .clientId("store-001")
                    .clientStore(store)
                    .config(ClientConfig.builder()
                            .recoveryIntervalMs(200)
                            .senderIntervalMs(100)
                            .retryBackoffBaseMs(50)
                            .build())
                    .build();
            try {
                client.start();
                long deadline = System.currentTimeMillis() + 5000;
                OutboxStatus status = null;
                while (System.currentTimeMillis() < deadline) {
                    status = store.findOutbox("stuck-1").orElse(null).getStatus();
                    if (status == OutboxStatus.PENDING || status == OutboxStatus.SENDING
                            || status == OutboxStatus.FAILED || status == OutboxStatus.DEAD_LETTER) {
                        if (status != OutboxStatus.SENDING) {
                            break; // 已复位并被发送尝试推进
                        }
                    }
                    Thread.sleep(50);
                }
                assertTrue("SENDING 记录应被恢复复位后推进，实际: " + status,
                        status == OutboxStatus.PENDING || status == OutboxStatus.FAILED
                                || status == OutboxStatus.DEAD_LETTER);
            } finally {
                client.close();
            }
        } finally {
            store.close();
        }
    }

    public static class Payload {
        public int qty;
    }
}
