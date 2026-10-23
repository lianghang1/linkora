package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.storage.server.MessageRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Base64;
import java.util.Optional;

/**
 * {@link MessageRepository} 的 JDBC 实现。
 *
 * <p>messageId 主键唯一约束承担幂等去重：重复保存静默忽略（保留原记录），
 * 与"先持久化后推送"的服务端语义配合。SQL 方言中立，payload 以 Base64 TEXT 存储，
 * 规避 BLOB/BYTEA 在不同数据库间的类型分歧。</p>
 *
 * @author lianghang
 */
public final class JdbcMessageRepository implements MessageRepository {

    private static final String COLUMNS =
            "message_id, client_id, message_type, payload_base64, created_at, expires_at, "
                    + "schema_version, trace_id, sequence_key";

    private final DataSource dataSource;

    public JdbcMessageRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public boolean save(LinkoraMessage message) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO lk_message (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, message.getMessageId());
            ps.setString(2, message.getClientId());
            ps.setString(3, message.getMessageType());
            ps.setString(4, Base64.getEncoder().encodeToString(message.getPayload()));
            ps.setLong(5, message.getCreatedAt());
            setNullableLong(ps, 6, message.getExpiresAt());
            ps.setInt(7, message.getSchemaVersion());
            setNullableString(ps, 8, message.getTraceId());
            setNullableString(ps, 9, message.getSequenceKey());
            ps.executeUpdate();
            return true;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 幂等语义：messageId 已存在，保留原记录、不覆盖、不报错
            return false;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "保存消息失败: " + message.getMessageId(), e, message.getMessageId());
        }
    }

    @Override
    public Optional<LinkoraMessage> findByMessageId(String messageId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_message WHERE message_id = ?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapMessage(rs)) : Optional.<LinkoraMessage>empty();
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询消息失败: " + messageId, e, messageId);
        }
    }

    private static LinkoraMessage mapMessage(ResultSet rs) throws SQLException {
        return LinkoraMessage.builder()
                .messageId(rs.getString("message_id"))
                .clientId(rs.getString("client_id"))
                .messageType(rs.getString("message_type"))
                .payload(Base64.getDecoder().decode(rs.getString("payload_base64")))
                .createdAt(rs.getLong("created_at"))
                .expiresAt(getNullableLong(rs, "expires_at"))
                .schemaVersion(rs.getInt("schema_version"))
                .traceId(rs.getString("trace_id"))
                .sequenceKey(rs.getString("sequence_key"))
                .build();
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
