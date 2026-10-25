package com.linkora.core.storage.client.jdbc;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.core.storage.embedded.EmbeddedSqliteDatabase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link JdbcClientStore} SQLite 冒烟测试：全方法基本读写、幂等去重、
 * CAS、顺序键让路、meta UPSERT、清理。SQL 方言中立，其余方言由
 * H2/MySQL 矩阵测试覆盖（与内嵌模式共用契约，语义见 SqliteClientStoreTest）。
 *
 * @author lianghang
 */
public class JdbcClientStoreSqliteTest {

    private EmbeddedSqliteDatabase db;
    private ClientStore store;

    @Before
    public void setUp() throws Exception {
        Path dbFile = Files.createTempDirectory("linkora-client-jdbc-").resolve("client.db");
        db = EmbeddedSqliteDatabase.atFile(dbFile.toString());
        store = new JdbcClientStore(db.dataSource());
    }

    @After
    public void tearDown() {
        db.close();
    }

    private static OutboxEntry outbox(String id, OutboxStatus status, long createdAt) {
        return new OutboxEntry(id, "order.created", new byte[]{1},
                status, createdAt, createdAt, 0, null, null, null, "key-a");
    }

    @Test
    public void saveIsIdempotentAndRoundTrips() {
        OutboxEntry entry = outbox("m-1", OutboxStatus.PENDING, 1000L);
        assertTrue(store.saveOutbox(entry));
        assertFalse(store.saveOutbox(entry));   // messageId 去重

        Optional<OutboxEntry> found = store.findOutbox("m-1");
        assertTrue(found.isPresent());
        assertEquals(OutboxStatus.PENDING, found.get().getStatus());
        assertEquals("key-a", found.get().getSequenceKey());
        assertFalse(store.findOutbox("m-x").isPresent());
    }

    @Test
    public void casUpdateOutboxStatus() {
        store.saveOutbox(outbox("m-1", OutboxStatus.PENDING, 1000L));
        OutboxEntry updated = new OutboxEntry("m-1", "order.created", new byte[]{1},
                OutboxStatus.SENDING, 1000L, 2000L, 1, null, null, null, "key-a");
        assertTrue(store.updateOutboxStatus(updated, OutboxStatus.PENDING));
        assertFalse(store.updateOutboxStatus(updated, OutboxStatus.PENDING));  // 已非 PENDING
        assertEquals(OutboxStatus.SENDING, store.findOutbox("m-1").get().getStatus());
    }

    @Test
    public void findOutboxByStatusWithRetryOrder() {
        store.saveOutbox(outbox("m-1", OutboxStatus.FAILED, 3000L));
        store.saveOutbox(outbox("m-2", OutboxStatus.FAILED, 1000L));
        store.saveOutbox(outbox("m-3", OutboxStatus.PENDING, 2000L));

        List<OutboxEntry> due = store.findOutbox(OutboxStatus.FAILED, 9999L, 10);
        assertEquals(2, due.size());
        assertEquals("m-2", due.get(0).getMessageId());   // created_at 升序
    }

    @Test
    public void sequenceKeyGate() {
        long t = System.currentTimeMillis();
        store.saveOutbox(outbox("m-earlier", OutboxStatus.PENDING, t));
        store.saveOutbox(outbox("m-later", OutboxStatus.PENDING, t));
        // 同毫秒入队：messageId 字典序 m-earlier < m-later → m-later 让路
        assertTrue(store.hasEarlierActiveInKey("key-a", "m-later"));
        assertFalse(store.hasEarlierActiveInKey("key-a", "m-earlier"));
        assertFalse(store.hasEarlierActiveInKey("key-none", "m-earlier"));

        // 队头进终态（SERVER_ACCEPTED）→ 不再阻塞
        OutboxEntry accepted = new OutboxEntry("m-earlier", "order.created", new byte[]{1},
                OutboxStatus.SERVER_ACCEPTED, t, t, 0, null, null, null, "key-a");
        assertTrue(store.updateOutboxStatus(accepted, OutboxStatus.PENDING));
        assertFalse(store.hasEarlierActiveInKey("key-a", "m-later"));
    }

    @Test
    public void metaUpsert() {
        store.saveMeta("k", "v1");
        store.saveMeta("k", "v2");
        assertEquals("v2", store.getMeta("k").get());
        assertFalse(store.getMeta("none").isPresent());
    }

    @Test
    public void cleanupOnlyAcceptedBeforeDeadline() {
        long t = System.currentTimeMillis();
        store.saveOutbox(outbox("m-acc", OutboxStatus.SERVER_ACCEPTED, t));
        store.saveOutbox(outbox("m-pend", OutboxStatus.PENDING, t));

        int deleted = store.deleteOutboxAcceptedBefore(t + 1000);
        assertEquals(1, deleted);
        assertTrue(store.findOutbox("m-acc").isPresent() == false);
        assertTrue(store.findOutbox("m-pend").isPresent());
    }
}
