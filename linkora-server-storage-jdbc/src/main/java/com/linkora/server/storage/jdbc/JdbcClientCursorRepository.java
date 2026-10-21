package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.storage.server.ClientCursorRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.OptionalLong;

/**
 * {@link ClientCursorRepository} 的 JDBC 实现。
 * 通过条件 UPDATE（{@code WHERE last_seq < ?}）保证游标单调前进，不允许回退。
 */
public final class JdbcClientCursorRepository implements ClientCursorRepository {

    private final DataSource dataSource;

    public JdbcClientCursorRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public void saveLastDeliveredSeq(String clientId, long seq) {
        try (Connection connection = dataSource.getConnection()) {
            // 先尝试单调前进：仅当新值更大时更新
            try (PreparedStatement advance = connection.prepareStatement(
                    "UPDATE lk_client_cursor SET last_seq = ? WHERE client_id = ? AND last_seq < ?")) {
                advance.setLong(1, seq);
                advance.setString(2, clientId);
                advance.setLong(3, seq);
                if (advance.executeUpdate() == 1) {
                    return;
                }
            }
            // 影响行数 0：行不存在（首次写入）或新值不大于已有值。
            // 尝试插入；主键冲突说明行已存在且新值不更大，属于合法的滞后写入，忽略。
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO lk_client_cursor (client_id, last_seq) VALUES (?, ?)")) {
                insert.setString(1, clientId);
                insert.setLong(2, seq);
                insert.executeUpdate();
            } catch (SQLIntegrityConstraintViolationException alreadyExistsOrStale) {
                // 游标已存在且当前 seq 不更大：单调性保持，忽略
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "保存客户端游标失败: " + clientId, e);
        }
    }

    @Override
    public OptionalLong getLastDeliveredSeq(String clientId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT last_seq FROM lk_client_cursor WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询客户端游标失败: " + clientId, e);
        }
    }
}
