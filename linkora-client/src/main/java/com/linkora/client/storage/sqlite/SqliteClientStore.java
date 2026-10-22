package com.linkora.client.storage.sqlite;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ClientStore} 的 SQLite 实现。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>独立 SQLite 文件，不触碰业务数据库；WAL + synchronous=FULL 保证
 *       进程崩溃与断电场景下的持久性（至少一次投递的落盘前提）。</li>
 *   <li>单连接 + 内部锁串行化所有访问：客户端本地队列写入压力小，
 *       串行化可完全规避 SQLite 写锁竞争与 SQLITE_BUSY，简化正确性推理。</li>
 *   <li>去重依赖 message_id 主键唯一约束，INSERT ... ON CONFLICT DO NOTHING
 *       返回影响行数 0 表示重复；CAS 更新通过 WHERE 中的期望状态实现。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class SqliteClientStore implements ClientStore, Closeable {

    private static final Logger log = LoggerFactory.getLogger(SqliteClientStore.class);

    private static final String OUTBOX_COLUMNS =
            "message_id, message_type, payload, status, created_at, updated_at, "
                    + "retry_count, next_retry_at, expires_at, last_error, ordering_key";

    private static final String INBOX_COLUMNS =
            "message_id, message_type, payload, receive_status, process_status, "
                    + "received_at, processed_at, retry_count, next_retry_at, last_error, ordering_key";

    private final Connection connection;
    private final Object lock = new Object();
    private volatile boolean closed;
    /** 宿主事务接管模式：连接由宿主持有与提交/回滚裁决，close() 不关闭连接。 */
    private final boolean hostManaged;

    /** 打开（必要时创建）指定路径的 SQLite 存储文件，父目录不存在时自动创建。 */
    public SqliteClientStore(Path dbFile) {
        this("jdbc:sqlite:" + dbFile.toAbsolutePath(), dbFile.toAbsolutePath().getParent());
    }

    /** 使用自定义 JDBC URL 打开存储（如 :memory: 或带参数的 URL）。 */
    public SqliteClientStore(String jdbcUrl) {
        this(jdbcUrl, null);
    }

    /**
     * 宿主事务接管构造：store 使用宿主提供的连接，所有写入加入宿主当前事务，
     * 严禁中途 commit/rollback/setAutoCommit——提交、回滚、连接配置全部由宿主裁决。
     *
     * <p>适用场景：宿主业务数据与客户端本地队列共用同一 SQLite 文件，
     * 要求"业务写 + outbox/inbox 写"同生共死（如 Spring @Transactional 包住
     * 业务写与 client.publish 的场景）。约定：</p>
     * <ul>
     *   <li>连接的 WAL/synchronous 等 PRAGMA 由宿主负责（宿主事务内执行
     *       {@code PRAGMA journal_mode} 会失败，故本构造不再设置）；</li>
     *   <li>构造时用宿主连接执行幂等 schema 迁移：连接处于自动提交时迁移
     *       自行提交；处于手动提交时加入宿主事务，随宿主一起提交/回滚；</li>
     *   <li>{@link #close()} 仅标记存储不可用，不关闭宿主连接。</li>
     * </ul>
     */
    public SqliteClientStore(Connection hostConnection) {
        try {
            SqliteSchema.migrate(hostConnection);
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "宿主接管模式下 SQLite schema 迁移失败", e);
        }
        this.connection = hostConnection;
        this.hostManaged = true;
        log.debug("SQLite 客户端存储已按宿主接管模式打开（连接由宿主管理）");
    }

    private SqliteClientStore(String jdbcUrl, Path parentDir) {
        Connection conn = null;
        try {
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }
            conn = DriverManager.getConnection(jdbcUrl);
            configurePragmas(conn);
            SqliteSchema.migrate(conn);
            this.connection = conn;
            this.hostManaged = false;
            log.debug("SQLite 客户端存储已打开: {}", jdbcUrl);
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignore) {
                    // 关闭失败不影响原始异常
                }
            }
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "打开 SQLite 客户端存储失败: " + jdbcUrl, e);
        }
    }

    private static void configurePragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode = WAL")) {
                rs.next();
                log.debug("SQLite journal_mode = {}", rs.getString(1));
            }
            st.execute("PRAGMA synchronous = FULL");
        }
    }

    // ---------------- outbox：线下 → 线上 ----------------

    @Override
    public boolean saveOutbox(OutboxEntry entry) {
        return exec("saveOutbox", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lk_outbox (" + OUTBOX_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(message_id) DO NOTHING")) {
                bindOutbox(ps, entry);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<OutboxEntry> findOutbox(String messageId) {
        return exec("findOutbox", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + OUTBOX_COLUMNS + " FROM lk_outbox WHERE message_id = ?")) {
                ps.setString(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapOutbox(rs)) : Optional.<OutboxEntry>empty();
                }
            }
        });
    }

    @Override
    public List<OutboxEntry> findOutbox(OutboxStatus status, long nextRetryBefore, int limit) {
        return exec("findOutboxByStatus", () -> {
            // next_retry_at 为 NULL 视为立即到期，排在最前
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + OUTBOX_COLUMNS + " FROM lk_outbox "
                            + "WHERE status = ? AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                            + "ORDER BY (next_retry_at IS NULL) DESC, next_retry_at ASC, created_at ASC "
                            + "LIMIT ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, nextRetryBefore);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<OutboxEntry> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapOutbox(rs));
                    }
                    return result;
                }
            }
        });
    }

    @Override
    public boolean updateOutboxStatus(OutboxEntry updated, OutboxStatus expected) {
        return exec("updateOutboxStatus", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE lk_outbox SET status = ?, updated_at = ?, retry_count = ?, "
                            + "next_retry_at = ?, last_error = ? "
                            + "WHERE message_id = ? AND status = ?")) {
                ps.setString(1, updated.getStatus().name());
                ps.setLong(2, updated.getUpdatedAt());
                ps.setInt(3, updated.getRetryCount());
                setNullableLong(ps, 4, updated.getNextRetryAt());
                setNullableString(ps, 5, updated.getLastError());
                ps.setString(6, updated.getMessageId());
                ps.setString(7, expected.name());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int deleteOutboxAcceptedBefore(long deadline) {
        return exec("deleteOutboxAcceptedBefore", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM lk_outbox WHERE status = 'SERVER_ACCEPTED' AND updated_at < ?")) {
                ps.setLong(1, deadline);
                return ps.executeUpdate();
            }
        });
    }

    // ---------------- inbox：线上 → 线下 ----------------

    @Override
    public boolean saveInbox(InboxEntry entry) {
        return exec("saveInbox", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lk_inbox (" + INBOX_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(message_id) DO NOTHING")) {
                bindInbox(ps, entry);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<InboxEntry> findInbox(String messageId) {
        return exec("findInbox", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_inbox WHERE message_id = ?")) {
                ps.setString(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapInbox(rs)) : Optional.<InboxEntry>empty();
                }
            }
        });
    }

    @Override
    public List<InboxEntry> findInboxPendingReceiveAck(int limit) {
        return exec("findInboxPendingReceiveAck", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_inbox "
                            + "WHERE receive_status = ? ORDER BY received_at ASC LIMIT ?")) {
                ps.setString(1, ReceiveStatus.PERSISTED.name());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<InboxEntry> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapInbox(rs));
                    }
                    return result;
                }
            }
        });
    }

    @Override
    public List<InboxEntry> findInboxByProcessStatus(ProcessStatus status, long nextRetryBefore, int limit) {
        return exec("findInboxByProcessStatus", () -> {
            // next_retry_at 为 NULL 视为立即到期，排在最前
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_inbox "
                            + "WHERE process_status = ? AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                            + "ORDER BY (next_retry_at IS NULL) DESC, next_retry_at ASC, received_at ASC "
                            + "LIMIT ?")) {
                ps.setString(1, status.name());
                ps.setLong(2, nextRetryBefore);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<InboxEntry> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapInbox(rs));
                    }
                    return result;
                }
            }
        });
    }

    @Override
    public boolean updateInboxReceiveStatus(InboxEntry updated, ReceiveStatus expected) {
        return exec("updateInboxReceiveStatus", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE lk_inbox SET receive_status = ? "
                            + "WHERE message_id = ? AND receive_status = ?")) {
                ps.setString(1, updated.getReceiveStatus().name());
                ps.setString(2, updated.getMessageId());
                ps.setString(3, expected.name());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean updateInboxProcessStatus(InboxEntry updated, ProcessStatus expected) {
        return exec("updateInboxProcessStatus", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE lk_inbox SET process_status = ?, processed_at = ?, retry_count = ?, "
                            + "next_retry_at = ?, last_error = ? "
                            + "WHERE message_id = ? AND process_status = ?")) {
                ps.setString(1, updated.getProcessStatus().name());
                setNullableLong(ps, 2, updated.getProcessedAt());
                ps.setInt(3, updated.getRetryCount());
                setNullableLong(ps, 4, updated.getNextRetryAt());
                setNullableString(ps, 5, updated.getLastError());
                ps.setString(6, updated.getMessageId());
                ps.setString(7, expected.name());
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int deleteInboxProcessedBefore(long deadline) {
        return exec("deleteInboxProcessedBefore", () -> {
            // 只清理业务处理完成且接收 ACK 已发送的记录；未完成 ACK 的消息不删，
            // 否则服务端会因收不到 ACK 持续补发
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM lk_inbox "
                            + "WHERE receive_status = 'RECEIVED_ACK_SENT' AND process_status = 'PROCESSED' "
                            + "AND COALESCE(processed_at, received_at) < ?")) {
                ps.setLong(1, deadline);
                return ps.executeUpdate();
            }
        });
    }

    // ---------------- meta：游标与本地元数据 ----------------

    @Override
    public void saveMeta(String key, String value) {
        exec("saveMeta", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lk_meta (meta_key, meta_value) VALUES (?, ?) "
                            + "ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<String> getMeta(String key) {
        return exec("getMeta", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT meta_value FROM lk_meta WHERE meta_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    // ---------------- 顺序键 ----------------
    //
    // 用 SQLite 的 rowid 做"更早"的判据：rowid 是插入顺序，等于发布/到达顺序，
    // 比 created_at 更可靠（同一毫秒内连续发布不会打平）。

    @Override
    public boolean hasEarlierActiveInKey(String orderingKey, String messageId) {
        if (orderingKey == null) {
            return false;
        }
        return exec("hasEarlierActiveInKey", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM lk_outbox "
                            + "WHERE ordering_key = ? "
                            + "AND status NOT IN ('SERVER_ACCEPTED', 'DEAD_LETTER') "
                            + "AND rowid < (SELECT rowid FROM lk_outbox WHERE message_id = ?) "
                            + "LIMIT 1")) {
                ps.setString(1, orderingKey);
                ps.setString(2, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public boolean hasEarlierUnsettledInbox(String orderingKey, String messageId) {
        if (orderingKey == null) {
            return false;
        }
        return exec("hasEarlierUnsettledInbox", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM lk_inbox "
                            + "WHERE ordering_key = ? "
                            + "AND process_status IN ('PENDING', 'RETRYING', 'PROCESSING') "
                            + "AND rowid < (SELECT rowid FROM lk_inbox WHERE message_id = ?) "
                            + "LIMIT 1")) {
                ps.setString(1, orderingKey);
                ps.setString(2, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    @Override
    public Optional<InboxEntry> findNextInboxInKey(String orderingKey) {
        if (orderingKey == null) {
            return Optional.empty();
        }
        return exec("findNextInboxInKey", () -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_inbox "
                            + "WHERE ordering_key = ? "
                            + "AND process_status IN ('PENDING', 'RETRYING') "
                            + "ORDER BY rowid ASC LIMIT 1")) {
                ps.setString(1, orderingKey);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapInbox(rs)) : Optional.<InboxEntry>empty();
                }
            }
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (hostManaged) {
                // 宿主接管模式：连接归宿主所有，仅标记存储不可用
                log.debug("SQLite 客户端存储已关闭（宿主连接保持打开）");
                return;
            }
            try {
                connection.close();
                log.debug("SQLite 客户端存储已关闭");
            } catch (SQLException e) {
                log.warn("关闭 SQLite 连接失败: {}", e.getMessage());
            }
        }
    }

    // ---------------- 内部工具 ----------------

    private interface SqlCallable<T> {
        T call() throws SQLException;
    }

    private <T> T exec(String operation, SqlCallable<T> body) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("SQLite 存储已关闭，拒绝操作: " + operation);
            }
            try {
                return body.call();
            } catch (SQLException e) {
                throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                        "SQLite 操作失败: " + operation, e);
            }
        }
    }

    private static void bindOutbox(PreparedStatement ps, OutboxEntry e) throws SQLException {
        ps.setString(1, e.getMessageId());
        ps.setString(2, e.getMessageType());
        ps.setBytes(3, e.getPayload());
        ps.setString(4, e.getStatus().name());
        ps.setLong(5, e.getCreatedAt());
        ps.setLong(6, e.getUpdatedAt());
        ps.setInt(7, e.getRetryCount());
        setNullableLong(ps, 8, e.getNextRetryAt());
        setNullableLong(ps, 9, e.getExpiresAt());
        setNullableString(ps, 10, e.getLastError());
        setNullableString(ps, 11, e.getOrderingKey());
    }

    private static void bindInbox(PreparedStatement ps, InboxEntry e) throws SQLException {
        ps.setString(1, e.getMessageId());
        ps.setString(2, e.getMessageType());
        ps.setBytes(3, e.getPayload());
        ps.setString(4, e.getReceiveStatus().name());
        ps.setString(5, e.getProcessStatus().name());
        ps.setLong(6, e.getReceivedAt());
        setNullableLong(ps, 7, e.getProcessedAt());
        ps.setInt(8, e.getRetryCount());
        setNullableLong(ps, 9, e.getNextRetryAt());
        setNullableString(ps, 10, e.getLastError());
        setNullableString(ps, 11, e.getOrderingKey());
    }

    private static OutboxEntry mapOutbox(ResultSet rs) throws SQLException {
        return new OutboxEntry(
                rs.getString("message_id"),
                rs.getString("message_type"),
                rs.getBytes("payload"),
                OutboxStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("retry_count"),
                getNullableLong(rs, "next_retry_at"),
                getNullableLong(rs, "expires_at"),
                rs.getString("last_error"),
                rs.getString("ordering_key"));
    }

    private static InboxEntry mapInbox(ResultSet rs) throws SQLException {
        return new InboxEntry(
                rs.getString("message_id"),
                rs.getString("message_type"),
                rs.getBytes("payload"),
                ReceiveStatus.valueOf(rs.getString("receive_status")),
                ProcessStatus.valueOf(rs.getString("process_status")),
                rs.getLong("received_at"),
                getNullableLong(rs, "processed_at"),
                rs.getInt("retry_count"),
                getNullableLong(rs, "next_retry_at"),
                rs.getString("last_error"),
                rs.getString("ordering_key"));
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
