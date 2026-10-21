package com.linkora.client.storage.sqlite;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SQLite 客户端存储持久化测试：去重、CAS、查询语义、重启恢复、故障场景。
 */
public class SqliteClientStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = System.currentTimeMillis();

    private Path dbPath() {
        return tmp.getRoot().toPath().resolve("client.db");
    }

    private static OutboxEntry outbox(String messageId, OutboxStatus status,
                                      long createdAt, long updatedAt,
                                      int retryCount, Long nextRetryAt, String lastError) {
        return new OutboxEntry(messageId, "ORDER_CREATED",
                ("payload-" + messageId).getBytes(StandardCharsets.UTF_8),
                status, createdAt, updatedAt, retryCount, nextRetryAt,
                null, lastError);
    }

    private static InboxEntry inbox(String messageId, ReceiveStatus receive, ProcessStatus process,
                                    long receivedAt, Long processedAt,
                                    int retryCount, Long nextRetryAt, String lastError) {
        return new InboxEntry(messageId, "ORDER_CREATED",
                ("payload-" + messageId).getBytes(StandardCharsets.UTF_8),
                receive, process, receivedAt, processedAt, retryCount, nextRetryAt, lastError);
    }

    // ---------------- schema 与打开/关闭 ----------------

    @Test
    public void journalModeIsWalAfterOpen() throws Exception {
        Path db = dbPath();
        SqliteClientStore store = new SqliteClientStore(db);
        try {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase());
            }
        } finally {
            store.close();
        }
    }

    @Test
    public void migrationIsIdempotentAndVersionIsLatest() throws Exception {
        Path db = dbPath();
        SqliteClientStore first = new SqliteClientStore(db);
        first.close();
        // 重新打开：迁移不得重复执行或报错
        SqliteClientStore second = new SqliteClientStore(db);
        try {
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT COALESCE(MAX(version), 0) FROM lk_schema_version")) {
                assertTrue(rs.next());
                assertEquals(SqliteSchema.LATEST_VERSION, rs.getInt(1));
            }
        } finally {
            second.close();
        }
    }

    @Test
    public void closedStoreRejectsOperations() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        store.close();
        store.close(); // 幂等关闭
        try {
            store.saveOutbox(outbox("m1", OutboxStatus.PENDING, NOW, NOW, 0, null, null));
            fail("已关闭的存储应拒绝操作");
        } catch (IllegalStateException expected) {
            // 预期行为
        }
    }

    @Test
    public void corruptFileIsRejected() throws Exception {
        Path db = dbPath();
        Files.write(db, ("this is not a sqlite database, just random garbage. ").getBytes(StandardCharsets.UTF_8));
        try {
            new SqliteClientStore(db);
            fail("损坏的数据库文件应拒绝打开");
        } catch (LinkoraException e) {
            assertEquals(LinkoraErrorCode.STORAGE_ERROR, e.getErrorCode());
        }
    }

    // ---------------- outbox ----------------

    @Test
    public void outboxSaveFindRoundtrip() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            OutboxEntry entry = outbox("ob-1", OutboxStatus.PENDING, NOW, NOW, 0, null, null);
            assertTrue(store.saveOutbox(entry));

            OutboxEntry loaded = store.findOutbox("ob-1").orElse(null);
            assertNotNull(loaded);
            assertEquals("ob-1", loaded.getMessageId());
            assertEquals("ORDER_CREATED", loaded.getMessageType());
            assertArrayEquals(entry.getPayload(), loaded.getPayload());
            assertEquals(OutboxStatus.PENDING, loaded.getStatus());
            assertEquals(NOW, loaded.getCreatedAt());
        } finally {
            store.close();
        }
    }

    @Test
    public void outboxDuplicateSaveIsRejected() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            assertTrue(store.saveOutbox(outbox("ob-dup", OutboxStatus.PENDING, NOW, NOW, 0, null, null)));
            // 同 messageId 重复入队：返回 false 且原记录不被覆盖
            assertFalse(store.saveOutbox(outbox("ob-dup", OutboxStatus.SERVER_ACCEPTED, NOW, NOW + 5, 3, null, "x")));
            assertEquals(OutboxStatus.PENDING, store.findOutbox("ob-dup").get().getStatus());
        } finally {
            store.close();
        }
    }

    @Test
    public void outboxQueryByStatusAndRetryTime() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            // PENDING 无 nextRetryAt → 立即到期
            assertTrue(store.saveOutbox(outbox("ob-p", OutboxStatus.PENDING, NOW, NOW, 0, null, null)));
            // FAILED 到期
            assertTrue(store.saveOutbox(outbox("ob-due", OutboxStatus.FAILED, NOW, NOW, 2, NOW - 1_000, "io")));
            // FAILED 未到期
            assertTrue(store.saveOutbox(outbox("ob-future", OutboxStatus.FAILED, NOW, NOW, 1, NOW + 60_000, "io")));
            // PENDING 但状态不同批
            assertTrue(store.saveOutbox(outbox("ob-acc", OutboxStatus.SERVER_ACCEPTED, NOW, NOW, 0, null, null)));

            List<OutboxEntry> pending = store.findOutbox(OutboxStatus.PENDING, NOW, 10);
            assertEquals(1, pending.size());
            assertEquals("ob-p", pending.get(0).getMessageId());

            List<OutboxEntry> dueFailed = store.findOutbox(OutboxStatus.FAILED, NOW, 10);
            assertEquals(1, dueFailed.size());
            assertEquals("ob-due", dueFailed.get(0).getMessageId());

            // PENDING + FAILED 到期条目合并调度查询
            List<OutboxEntry> allDue = store.findOutbox(OutboxStatus.PENDING, NOW, 10);
            allDue.addAll(store.findOutbox(OutboxStatus.FAILED, NOW, 10));
            assertEquals(2, allDue.size());
        } finally {
            store.close();
        }
    }

    @Test
    public void outboxCasUpdateSemantics() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            store.saveOutbox(outbox("ob-cas", OutboxStatus.PENDING, NOW, NOW, 0, null, null));

            // PENDING → SENDING
            OutboxEntry sending = store.findOutbox("ob-cas").get()
                    .with(OutboxStatus.SENDING, 0, null, null, NOW + 1);
            assertTrue(store.updateOutboxStatus(sending, OutboxStatus.PENDING));

            // 状态已推进，基于旧期望的 CAS 失败（并发发送方竞争败者）
            OutboxEntry stale = store.findOutbox("ob-cas").get()
                    .with(OutboxStatus.SERVER_ACCEPTED, 0, null, null, NOW + 2);
            assertFalse(store.updateOutboxStatus(stale, OutboxStatus.PENDING));
            assertEquals(OutboxStatus.SENDING, store.findOutbox("ob-cas").get().getStatus());

            // SENDING → FAILED，携带重试元数据
            OutboxEntry failed = store.findOutbox("ob-cas").get()
                    .with(OutboxStatus.FAILED, 1, NOW + 30_000, "connect timeout", NOW + 3);
            assertTrue(store.updateOutboxStatus(failed, OutboxStatus.SENDING));
            OutboxEntry loaded = store.findOutbox("ob-cas").get();
            assertEquals(OutboxStatus.FAILED, loaded.getStatus());
            assertEquals(1, loaded.getRetryCount());
            assertEquals(Long.valueOf(NOW + 30_000), loaded.getNextRetryAt());
            assertEquals("connect timeout", loaded.getLastError());
        } finally {
            store.close();
        }
    }

    @Test
    public void outboxCleanupOnlyRemovesAcceptedBeforeDeadline() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            store.saveOutbox(outbox("ob-old-acc", OutboxStatus.SERVER_ACCEPTED, NOW, NOW - 10_000, 0, null, null));
            store.saveOutbox(outbox("ob-new-acc", OutboxStatus.SERVER_ACCEPTED, NOW, NOW, 0, null, null));
            store.saveOutbox(outbox("ob-old-pending", OutboxStatus.PENDING, NOW, NOW - 10_000, 0, null, null));

            int deleted = store.deleteOutboxAcceptedBefore(NOW);
            assertEquals(1, deleted);
            assertFalse(store.findOutbox("ob-old-acc").isPresent());
            assertTrue(store.findOutbox("ob-new-acc").isPresent());
            assertTrue(store.findOutbox("ob-old-pending").isPresent());
        } finally {
            store.close();
        }
    }

    // ---------------- inbox ----------------

    @Test
    public void inboxSaveDedupAndPendingAckQuery() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            assertTrue(store.saveInbox(inbox("in-2", ReceiveStatus.PERSISTED, ProcessStatus.PENDING, NOW + 2, null, 0, null, null)));
            assertTrue(store.saveInbox(inbox("in-1", ReceiveStatus.PERSISTED, ProcessStatus.PENDING, NOW + 1, null, 0, null, null)));
            // 重复接收（服务端补发同一消息）：拒绝写入
            assertFalse(store.saveInbox(inbox("in-1", ReceiveStatus.PERSISTED, ProcessStatus.PENDING, NOW + 9, null, 0, null, null)));

            List<InboxEntry> pending = store.findInboxPendingReceiveAck(10);
            assertEquals(2, pending.size());
            assertEquals("in-1", pending.get(0).getMessageId());
            assertEquals("in-2", pending.get(1).getMessageId());

            // CAS 推进接收 ACK 状态
            InboxEntry acked = store.findInbox("in-1").get()
                    .with(ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PENDING, 0, null, null, null);
            assertTrue(store.updateInboxReceiveStatus(acked, ReceiveStatus.PERSISTED));
            // 重复 ACK 更新基于旧期望 → 失败（幂等保护）
            assertFalse(store.updateInboxReceiveStatus(acked, ReceiveStatus.PERSISTED));

            assertEquals(1, store.findInboxPendingReceiveAck(10).size());
        } finally {
            store.close();
        }
    }

    @Test
    public void inboxProcessCasAndRetryQuery() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            store.saveInbox(inbox("in-cas", ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PENDING, NOW, null, 0, null, null));

            // PENDING → PROCESSING
            assertTrue(store.updateInboxProcessStatus(
                    store.findInbox("in-cas").get().with(ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PROCESSING, 0, null, null, null),
                    ProcessStatus.PENDING));
            // 旧期望竞争失败
            assertFalse(store.updateInboxProcessStatus(
                    store.findInbox("in-cas").get().with(ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PROCESSED, 0, null, null, NOW),
                    ProcessStatus.PENDING));

            // PROCESSING → FAILED（带重试元数据）
            assertTrue(store.updateInboxProcessStatus(
                    store.findInbox("in-cas").get().with(ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.FAILED, 1, NOW - 500, "biz error", null),
                    ProcessStatus.PROCESSING));
            assertEquals(1, store.findInboxByProcessStatus(ProcessStatus.FAILED, NOW, 10).size());

            // FAILED → RETRYING，重试到期后可被查询
            assertTrue(store.updateInboxProcessStatus(
                    store.findInbox("in-cas").get().with(ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.RETRYING, 2, NOW - 100, "biz error", null),
                    ProcessStatus.FAILED));
            List<InboxEntry> due = store.findInboxByProcessStatus(ProcessStatus.RETRYING, NOW, 10);
            assertEquals(1, due.size());
            assertEquals("in-cas", due.get(0).getMessageId());
            assertEquals(2, due.get(0).getRetryCount());

            // RETRYING → PROCESSED，processedAt 记录
            assertTrue(store.updateInboxProcessStatus(
                    store.findInbox("in-cas").get().with(ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PROCESSED, 2, null, null, NOW),
                    ProcessStatus.RETRYING));
            assertEquals(Long.valueOf(NOW), store.findInbox("in-cas").get().getProcessedAt());
            assertEquals(0, store.findInboxByProcessStatus(ProcessStatus.RETRYING, NOW, 10).size());
        } finally {
            store.close();
        }
    }

    @Test
    public void inboxCleanupRequiresProcessedAndAcked() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            store.saveInbox(inbox("in-done", ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PROCESSED, NOW - 10_000, NOW - 9_000, 0, null, null));
            // 处理完成但接收 ACK 未发送：不得删除（否则服务端会持续补发）
            store.saveInbox(inbox("in-no-ack", ReceiveStatus.PERSISTED, ProcessStatus.PROCESSED, NOW - 10_000, NOW - 9_000, 0, null, null));
            store.saveInbox(inbox("in-recent", ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PROCESSED, NOW, NOW, 0, null, null));
            store.saveInbox(inbox("in-failed", ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.FAILED, NOW - 10_000, null, 1, NOW, "e"));

            int deleted = store.deleteInboxProcessedBefore(NOW);
            assertEquals(1, deleted);
            assertFalse(store.findInbox("in-done").isPresent());
            assertTrue(store.findInbox("in-no-ack").isPresent());
            assertTrue(store.findInbox("in-recent").isPresent());
            assertTrue(store.findInbox("in-failed").isPresent());
        } finally {
            store.close();
        }
    }

    @Test
    public void concurrentProcessCasHasExactlyOneWinner() throws Exception {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            store.saveInbox(inbox("in-race", ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PENDING, NOW, null, 0, null, null));

            final CountDownLatch start = new CountDownLatch(1);
            final AtomicInteger winners = new AtomicInteger();
            Runnable contender = new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        // 各自读取最新状态后 CAS PENDING → PROCESSED，只有一个能成功
                        InboxEntry latest = store.findInbox("in-race").get();
                        boolean won = store.updateInboxProcessStatus(
                                latest.with(latest.getReceiveStatus(), ProcessStatus.PROCESSED, 0, null, null, NOW),
                                ProcessStatus.PENDING);
                        if (won) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            Thread t1 = new Thread(contender);
            Thread t2 = new Thread(contender);
            t1.start();
            t2.start();
            start.countDown();
            t1.join(10_000);
            t2.join(10_000);

            assertEquals(1, winners.get());
            assertEquals(ProcessStatus.PROCESSED, store.findInbox("in-race").get().getProcessStatus());
        } finally {
            store.close();
        }
    }

    // ---------------- meta ----------------

    @Test
    public void metaUpsertSemantics() {
        SqliteClientStore store = new SqliteClientStore(dbPath());
        try {
            assertFalse(store.getMeta("cursor").isPresent());
            store.saveMeta("cursor", "100");
            assertEquals("100", store.getMeta("cursor").get());
            // 覆盖写
            store.saveMeta("cursor", "200");
            assertEquals("200", store.getMeta("cursor").get());
        } finally {
            store.close();
        }
    }

    // ---------------- 重启恢复 ----------------

    @Test
    public void dataSurvivesRestart() {
        Path db = dbPath();
        SqliteClientStore first = new SqliteClientStore(db);
        try {
            first.saveOutbox(outbox("ob-keep", OutboxStatus.PENDING, NOW, NOW, 0, null, null));
            first.saveInbox(inbox("in-keep", ReceiveStatus.PERSISTED, ProcessStatus.PENDING, NOW, null, 0, null, null));
            first.saveMeta("last_event_id", "42");
        } finally {
            first.close();
        }

        SqliteClientStore second = new SqliteClientStore(db);
        try {
            OutboxEntry outbox = second.findOutbox("ob-keep").orElse(null);
            assertNotNull(outbox);
            assertEquals(OutboxStatus.PENDING, outbox.getStatus());

            InboxEntry inbox = second.findInbox("in-keep").orElse(null);
            assertNotNull(inbox);
            assertEquals(ReceiveStatus.PERSISTED, inbox.getReceiveStatus());

            assertEquals("42", second.getMeta("last_event_id").get());
        } finally {
            second.close();
        }
    }
}
