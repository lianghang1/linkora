package com.linkora.example.txclient;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 宿主库（H2）版 {@link ClientStore}：客户端事务接管示例的核心。
 *
 * <p>连接来源是 {@code TransactionAwareDataSourceProxy} 包装的宿主数据源，因此：</p>
 * <ul>
 *   <li>宿主事务内调用（如 {@code @Transactional} 业务方法里的
 *       {@code client.publish(...)} → {@code saveOutbox}）自动加入当前事务——
 *       业务回滚则 outbox 记录随之消失；</li>
 *   <li>事务外调用（客户端后台发送/恢复线程）走独立连接逐语句提交，互不干扰。</li>
 * </ul>
 *
 * <p>幂等写入用"唯一约束 + 捕获重复键"实现（方言中立），CAS 更新通过
 * WHERE 期望状态实现，与 linkora-core 契约一致（docs/storage-spi.md）。</p>
 *
 * @author lianghang
 */
public class H2ClientStore implements ClientStore {

    private final DataSource dataSource;

    public H2ClientStore(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    // ---------------- outbox：线下 → 线上 ----------------

    @Override
    public boolean saveOutbox(OutboxEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO lk_outbox (message_id, message_type, payload, status, "
                             + "created_at, updated_at, retry_count, next_retry_at, expires_at, last_error) "
                             + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            bindOutbox(ps, entry);
            return ps.executeUpdate() == 1;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 幂等语义：messageId 已存在，保留原记录
            return false;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "saveOutbox 失败: " + entry.getMessageId(), e);
        }
    }

    @Override
    public Optional<OutboxEntry> findOutbox(String messageId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT message_id, message_type, payload, status, created_at, updated_at, "
                             + "retry_count, next_retry_at, expires_at, last_error "
                             + "FROM lk_outbox WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapOutbox(rs)) : Optional.<OutboxEntry>empty();
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "findOutbox 失败: " + messageId, e);
        }
    }

    @Override
    public List<OutboxEntry> findOutbox(OutboxStatus status, long nextRetryBefore, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT message_id, message_type, payload, status, created_at, updated_at, "
                             + "retry_count, next_retry_at, expires_at, last_error "
                             + "FROM lk_outbox WHERE status = ? "
                             + "AND (next_retry_at IS NULL OR next_retry_at <= ?) "
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
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "findOutbox(按状态) 失败", e);
        }
    }

    @Override
    public boolean updateOutboxStatus(OutboxEntry updated, OutboxStatus expected) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
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
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "updateOutboxStatus 失败: " + updated.getMessageId(), e);
        }
    }

    @Override
    public int deleteOutboxAcceptedBefore(long deadline) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM lk_outbox WHERE status = 'SERVER_ACCEPTED' AND updated_at < ?")) {
            ps.setLong(1, deadline);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "deleteOutboxAcceptedBefore 失败", e);
        }
    }

    // ---------------- inbox：线上 → 线下 ----------------

    @Override
    public boolean saveInbox(InboxEntry entry) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO lk_inbox (message_id, message_type, payload, receive_status, "
                             + "process_status, received_at, processed_at, retry_count, next_retry_at, last_error) "
                             + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            bindInbox(ps, entry);
            return ps.executeUpdate() == 1;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 接收去重落点
            return false;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "saveInbox 失败: " + entry.getMessageId(), e);
        }
    }

    @Override
    public Optional<InboxEntry> findInbox(String messageId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT message_id, message_type, payload, receive_status, process_status, "
                             + "received_at, processed_at, retry_count, next_retry_at, last_error "
                             + "FROM lk_inbox WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInbox(rs)) : Optional.<InboxEntry>empty();
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "findInbox 失败: " + messageId, e);
        }
    }

    @Override
    public List<InboxEntry> findInboxPendingReceiveAck(int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT message_id, message_type, payload, receive_status, process_status, "
                             + "received_at, processed_at, retry_count, next_retry_at, last_error "
                             + "FROM lk_inbox WHERE receive_status = ? ORDER BY received_at ASC LIMIT ?")) {
            ps.setString(1, ReceiveStatus.PERSISTED.name());
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<InboxEntry> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapInbox(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "findInboxPendingReceiveAck 失败", e);
        }
    }

    @Override
    public List<InboxEntry> findInboxByProcessStatus(ProcessStatus status,
                                                     long nextRetryBefore, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT message_id, message_type, payload, receive_status, process_status, "
                             + "received_at, processed_at, retry_count, next_retry_at, last_error "
                             + "FROM lk_inbox WHERE process_status = ? "
                             + "AND (next_retry_at IS NULL OR next_retry_at <= ?) "
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
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "findInboxByProcessStatus 失败", e);
        }
    }

    @Override
    public boolean updateInboxReceiveStatus(InboxEntry updated, ReceiveStatus expected) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE lk_inbox SET receive_status = ? "
                             + "WHERE message_id = ? AND receive_status = ?")) {
            ps.setString(1, updated.getReceiveStatus().name());
            ps.setString(2, updated.getMessageId());
            ps.setString(3, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "updateInboxReceiveStatus 失败: " + updated.getMessageId(), e);
        }
    }

    @Override
    public boolean updateInboxProcessStatus(InboxEntry updated, ProcessStatus expected) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
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
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "updateInboxProcessStatus 失败: " + updated.getMessageId(), e);
        }
    }

    @Override
    public int deleteInboxProcessedBefore(long deadline) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM lk_inbox "
                             + "WHERE receive_status = 'RECEIVED_ACK_SENT' AND process_status = 'PROCESSED' "
                             + "AND COALESCE(processed_at, received_at) < ?")) {
            ps.setLong(1, deadline);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "deleteInboxProcessedBefore 失败", e);
        }
    }

    // ---------------- meta ----------------

    @Override
    public void saveMeta(String key, String value) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "MERGE INTO lk_meta (meta_key, meta_value) KEY (meta_key) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "saveMeta 失败: " + key, e);
        }
    }

    @Override
    public Optional<String> getMeta(String key) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT meta_value FROM lk_meta WHERE meta_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.<String>empty();
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "getMeta 失败: " + key, e);
        }
    }

    // ---------------- 绑定与映射 ----------------

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
                rs.getString("last_error"));
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
                rs.getString("last_error"));
    }

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.VARCHAR);
        } else {
            ps.setString(index, value);
        }
    }
}
