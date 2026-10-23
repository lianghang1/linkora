package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.ConnectionState;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

/**
 * {@link ClientRegistry} 的 JDBC 实现。注册幂等：重复注册保留首次记录。
 *
 * @author lianghang
 */
public final class JdbcClientRegistry implements ClientRegistry {

    private static final String COLUMNS =
            "client_id, enabled, created_at, connection_state, last_active_at";

    private final DataSource dataSource;

    public JdbcClientRegistry(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    @Override
    public void register(ClientRegistration registration) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO lk_client (" + COLUMNS + ") VALUES (?,?,?,?,NULL)")) {
            ps.setString(1, registration.getClientId());
            ps.setBoolean(2, registration.isEnabled());
            ps.setLong(3, registration.getCreatedAt());
            ps.setString(4, ConnectionState.DISCONNECTED.name());
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 幂等语义：重复注册忽略
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "注册客户端失败: " + registration.getClientId(), e);
        }
    }

    @Override
    public Optional<ClientRegistration> findByClientId(String clientId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT client_id, enabled, created_at FROM lk_client WHERE client_id = ?")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ClientRegistration(
                        rs.getString("client_id"),
                        rs.getBoolean("enabled"),
                        rs.getLong("created_at")));
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询客户端注册失败: " + clientId, e);
        }
    }

    @Override
    public java.util.List<ClientRegistration> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT client_id, enabled, created_at FROM lk_client ORDER BY client_id ASC");
             ResultSet rs = ps.executeQuery()) {
            java.util.List<ClientRegistration> result = new java.util.ArrayList<>();
            while (rs.next()) {
                result.add(new ClientRegistration(
                        rs.getString("client_id"),
                        rs.getBoolean("enabled"),
                        rs.getLong("created_at")));
            }
            return result;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询全部客户端注册失败", e);
        }
    }

    @Override
    public void updateConnectionState(String clientId, ConnectionState state, long lastActiveAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE lk_client SET connection_state = ?, last_active_at = ? WHERE client_id = ?")) {
            ps.setString(1, state.name());
            ps.setLong(2, lastActiveAt);
            ps.setString(3, clientId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "更新客户端连接状态失败: " + clientId, e);
        }
    }
}
