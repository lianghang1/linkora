package com.linkora.core.storage.client.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.core.storage.jdbc.JdbcErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link ClientStore} 的通用 JDBC 实现（datasource 模式）：客户端本地队列
 * 落在宿主 {@link DataSource} 指向的数据库中，与业务数据同库。
 *
 * <p><b>事务语义（本实现的核心价值）</b>：连接一律取自 {@code dataSource.getConnection()}，
 * 本实现绝不 commit / rollback / setAutoCommit——</p>
 * <ul>
 *   <li>宿主以事务感知数据源（如 Spring {@code TransactionAwareDataSourceProxy}）
 *       构造本类时，所有写入自动加入宿主当前事务：业务写与 outbox/inbox 写
 *       同生共死（transactional outbox）；</li>
 *   <li>以普通池化数据源构造时，每个操作独立提交，行为与内嵌模式一致。</li>
 * </ul>
 *
 * <p><b>方言</b>：幂等写入用"INSERT + 唯一键冲突捕获"实现（方言中立，
 * 唯一键约束本身就是幂等保证，不依赖任何方言特性；H2 常规模式不支持
 * ON CONFLICT / INSERT IGNORE，实测验证）。其余 SQL（CAS、LIMIT、COALESCE）
 * 三库通用。</p>
 *
 * <p><b>与内嵌模式的差异</b>：表名带 {@code lk_client_} 前缀（宿主库中避免歧义）；
 * "更早同键未终结消息"判断用 (created_at, message_id) 复合比较而非 SQLite 的
 * rowid（MySQL/H2 无 rowid）——同一存储实例内语义自洽，不混用。</p>
 *
 * @author lianghang
 */
public final class JdbcClientStore implements ClientStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcClientStore.class);

    private static final String OUTBOX_COLUMNS =
            "message_id, message_type, payload, status, created_at, updated_at, "
                    + "retry_count, next_retry_at, expires_at, last_error, sequence_key";

    private static final String INBOX_COLUMNS =
            "message_id, message_type, payload, receive_status, process_status, "
                    + "received_at, processed_at, retry_count, next_retry_at, last_error, sequence_key";

    private final DataSource dataSource;

    /** 构造并执行 schema 迁移（幂等，宿主事务内则随宿主提交）。 */
    public JdbcClientStore(DataSource dataSource) {
        this.dataSource = dataSource;
        try {
            JdbcClientSchema.migrate(dataSource);
        } catch (LinkoraException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "打开 JDBC 客户端存储失败", e);
        }
        log.info("JDBC 客户端存储已就绪（datasource 模式）");
    }

    // ---------------- outbox：线下 → 线上 ----------------

    @Override
    public boolean saveOutbox(OutboxEntry entry) {
        return exec("saveOutbox", () -> {
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO lk_client_outbox (" + OUTBOX_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                bindOutbox(ps, entry);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                // 幂等去重落点：messageId 唯一键冲突 → 已存在，返回 false；
                // 方言中立（唯一键约束本身就是幂等保证，无需 IGNORE/ON CONFLICT 方言特性）
                if (JdbcErrors.isIntegrityViolation(e)) {
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public Optional<OutboxEntry> findOutbox(String messageId) {
        return exec("findOutbox", () -> {
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + OUTBOX_COLUMNS + " FROM lk_client_outbox WHERE message_id = ?")) {
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + OUTBOX_COLUMNS + " FROM lk_client_outbox "
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "UPDATE lk_client_outbox SET status = ?, updated_at = ?, retry_count = ?, "
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM lk_client_outbox "
                            + "WHERE status = 'SERVER_ACCEPTED' AND updated_at < ?")) {
                ps.setLong(1, deadline);
                return ps.executeUpdate();
            }
        });
    }

    // ---------------- inbox：线上 → 线下 ----------------

    @Override
    public boolean saveInbox(InboxEntry entry) {
        return exec("saveInbox", () -> {
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO lk_client_inbox (" + INBOX_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                bindInbox(ps, entry);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                // 幂等去重落点：messageId 唯一键冲突 → 已存在，返回 false
                if (JdbcErrors.isIntegrityViolation(e)) {
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public Optional<InboxEntry> findInbox(String messageId) {
        return exec("findInbox", () -> {
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_client_inbox WHERE message_id = ?")) {
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_client_inbox "
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + INBOX_COLUMNS + " FROM lk_client_inbox "
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "UPDATE lk_client_inbox SET receive_status = ? "
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "UPDATE lk_client_inbox SET process_status = ?, processed_at = ?, retry_count = ?, "
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
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM lk_client_inbox "
                            + "WHERE receive_status = 'RECEIVED_ACK_SENT' AND process_status = 'PROCESSED' "
                            + "AND COALESCE(processed_at, received_at) < ?")) {
                ps.setLong(1, deadline);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public boolean hasEarlierActiveInKey(final String sequenceKey, final String messageId) {
        if (sequenceKey == null || sequenceKey.trim().isEmpty()) {
            return false;
        }
        return exec("hasEarlierActiveInKey", () -> {
            // 同键更早入队且未终结（非 SERVER_ACCEPTED / DEAD_LETTER）的消息存在 → 本条让路。
            // 死信不算活动消息：与下行语义一致，队头进死信放行后续，不永久阻塞。
            // "更早"用 (created_at, message_id) 复合比较：通用实现无 rowid（仅 SQLite 有），
            // 同毫秒入队时以 messageId 字典序定序——同一存储实例内自洽。
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM lk_client_outbox o "
                            + "WHERE o.sequence_key = ? AND o.message_id <> ? "
                            + "AND o.status NOT IN ('SERVER_ACCEPTED', 'DEAD_LETTER') "
                            + "AND (o.created_at < (SELECT c.created_at FROM lk_client_outbox c "
                            + "                      WHERE c.message_id = ?) "
                            + "     OR (o.created_at = (SELECT c.created_at FROM lk_client_outbox c "
                            + "                         WHERE c.message_id = ?) "
                            + "          AND o.message_id < ?)) "
                            + "LIMIT 1")) {
                ps.setString(1, sequenceKey);
                ps.setString(2, messageId);
                ps.setString(3, messageId);
                ps.setString(4, messageId);
                ps.setString(5, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    // ---------------- meta：游标与本地元数据 ----------------

    @Override
    public void saveMeta(String key, String value) {
        exec("saveMeta", () -> {
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO lk_client_meta (meta_key, meta_value) VALUES (?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
                return null;
            } catch (SQLException conflict) {
                // UPSERT 后半段：已存在则更新（方言中立的实现）
                if (!JdbcErrors.isIntegrityViolation(conflict)) {
                    throw conflict;
                }
                try (Connection conn = connection();
                        PreparedStatement ps = conn.prepareStatement(
                        "UPDATE lk_client_meta SET meta_value = ? WHERE meta_key = ?")) {
                    ps.setString(2, key);
                    ps.setString(1, value);
                    ps.executeUpdate();
                    return null;
                }
            }
        });
    }

    @Override
    public Optional<String> getMeta(String key) {
        return exec("getMeta", () -> {
            try (Connection conn = connection();
                    PreparedStatement ps = conn.prepareStatement(
                    "SELECT meta_value FROM lk_client_meta WHERE meta_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
                }
            }
        });
    }

    // ---------------- 内部工具 ----------------

    private interface SqlCallable<T> {
        T call() throws SQLException;
    }

    private <T> T exec(String operation, SqlCallable<T> body) {
        try {
            return body.call();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "JDBC 客户端存储操作失败: " + operation, e);
        }
    }

    private Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 生命周期对齐：连接全部取自宿主数据源/连接池，本实现无独占资源；
     * close() 仅为与内嵌模式（SqliteClientStore）的 Bean 生命周期语义对齐而存在。
     */
    public void close() {
        log.debug("JDBC 客户端存储已关闭（连接归宿主连接池，无需释放）");
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
        setNullableString(ps, 11, e.getSequenceKey());
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
        setNullableString(ps, 11, e.getSequenceKey());
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
                rs.getString("sequence_key"));
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
                rs.getString("sequence_key"));
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
