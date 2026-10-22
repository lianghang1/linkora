package com.linkora.client.storage.sqlite;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 宿主事务接管模式测试：{@link SqliteClientStore#SqliteClientStore(Connection)}
 * 下所有写入（含 schema 迁移）加入宿主当前事务，仓储严禁中途 commit/rollback，
 * 提交、回滚由宿主裁决；close() 不关闭宿主连接。SQLite DDL 具备事务性，
 * 因此迁移+写入可整体验证"同生共死"（MySQL DDL 隐式提交，见服务端 IT 的差异说明）。
 *
 * @author lianghang
 */
public class SqliteClientStoreHostTxTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final long NOW = System.currentTimeMillis();

    private static OutboxEntry outbox(String messageId, OutboxStatus status) {
        return new OutboxEntry(messageId, "ORDER_CREATED",
                ("payload-" + messageId).getBytes(StandardCharsets.UTF_8),
                status, NOW, NOW, 0, null, null, null);
    }

    private static InboxEntry inbox(String messageId) {
        return new InboxEntry(messageId, "REVIEW_CREATED",
                ("payload-" + messageId).getBytes(StandardCharsets.UTF_8),
                ReceiveStatus.RECEIVED_ACK_SENT, ProcessStatus.PENDING, NOW, null, 0, null, null);
    }

    private Path dbPath() {
        return tmp.getRoot().toPath().resolve("host-tx-client.db");
    }

    /** 用例 A：迁移 + 业务写入同入宿主事务，回滚全部消失、提交全部生效。 */
    @Test
    public void migrationAndWritesJoinHostTransaction() throws Exception {
        String url = "jdbc:sqlite:" + dbPath().toAbsolutePath();
        Connection host = DriverManager.getConnection(url);
        host.setAutoCommit(false);

        // 接管构造：迁移加入宿主事务（未提交，其他连接连表都不可见）
        SqliteClientStore store = new SqliteClientStore(host);
        assertTrue(store.saveOutbox(outbox("m1", OutboxStatus.PENDING)));
        assertTrue(store.saveInbox(inbox("in1")));
        store.saveMeta("boot-flag", "1");

        try (Connection observer = DriverManager.getConnection(url)) {
            try {
                observer.createStatement().executeQuery("SELECT COUNT(*) FROM lk_outbox");
                throw new AssertionError("宿主事务提交前，未提交的建表对其他连接应不可见");
            } catch (SQLException expected) {
                // SQLite 事务性 DDL：表尚未提交，查询报 no such table
            }
        }

        // 宿主提交：迁移 + 三类写入整体生效
        host.commit();
        try (Connection observer = DriverManager.getConnection(url);
             ResultSet rs = observer.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM lk_outbox")) {
            rs.next();
            assertEquals(1, rs.getLong(1));
        }
        assertTrue(store.findOutbox("m1").isPresent());
        assertEquals("1", store.getMeta("boot-flag").orElse(""));

        // 再次开启事务：rollback 撤销本事务内写入，不影响已提交数据
        store.saveOutbox(outbox("m2", OutboxStatus.PENDING));
        host.rollback();
        assertFalse(store.findOutbox("m2").isPresent());
        assertTrue(store.findOutbox("m1").isPresent());

        // 连接状态保持：仓储全程未动 autoCommit
        assertFalse("仓储不得破坏宿主连接的 autoCommit 状态", host.getAutoCommit());

        // close() 仅标记存储不可用，宿主连接仍可用
        store.close();
        try (Statement st = host.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM lk_meta")) {
            rs.next();
            assertEquals(1, rs.getLong(1));
        }
        host.close();
    }

    /** 用例 B：CAS 状态推进加入宿主事务，随宿主回滚而消失。 */
    @Test
    public void casUpdateJoinsHostTransactionAndRollsBack() throws Exception {
        String url = "jdbc:sqlite:" + dbPath().toAbsolutePath();
        Connection host = DriverManager.getConnection(url);

        // 自管模式先建表（迁移自行提交）
        SqliteClientStore bootstrap = new SqliteClientStore(host);
        bootstrap.close();

        host.setAutoCommit(false);
        SqliteClientStore store = new SqliteClientStore(host);
        store.saveOutbox(outbox("cas-1", OutboxStatus.PENDING));
        host.commit(); // 初始条目先落库

        // 新事务内 CAS 推进
        assertTrue(store.updateOutboxStatus(
                outbox("cas-1", OutboxStatus.SERVER_ACCEPTED), OutboxStatus.PENDING));
        assertEquals(OutboxStatus.SERVER_ACCEPTED,
                store.findOutbox("cas-1").get().getStatus());

        // 宿主回滚：CAS 结果随事务消失，条目回到已提交的 PENDING
        host.rollback();
        assertEquals(OutboxStatus.PENDING, store.findOutbox("cas-1").get().getStatus());
        assertFalse(host.getAutoCommit());
        store.close();
        host.close();
    }
}
