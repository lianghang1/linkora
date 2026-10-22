package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;

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
 * {@link DeliveryRepository} 的 JDBC 实现。
 *
 * <p>关键机制：</p>
 * <ul>
 *   <li>CAS 状态更新：{@code WHERE ... AND state = ?}，影响行数 0 表示竞争失败，
 *       旧请求无法回退已完成状态。</li>
 *   <li>per-client seq 原子分配：在单事务内 INSERT-OR-SKIP 初始化行、
 *       {@code UPDATE last_seq = last_seq + 1}（行锁串行化）、同事务读回，
 *       并发分配严格递增且无重复、无跳号。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class JdbcDeliveryRepository implements DeliveryRepository {

    private static final String COLUMNS =
            "message_id, client_id, seq, state, retry_count, next_retry_at, last_error, created_at, updated_at";

    /** 终态集合（与 MessageState.isTerminal() 一致，用于补发查询过滤）。 */
    private static final String TERMINAL_STATE_LIST = buildTerminalStateList();

    private final DataSource dataSource;

    public JdbcDeliveryRepository(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
    }

    private static String buildTerminalStateList() {
        StringBuilder sb = new StringBuilder();
        for (MessageState state : MessageState.values()) {
            if (state.isTerminal()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append('\'').append(state.name()).append('\'');
            }
        }
        return sb.toString();
    }

    @Override
    public void init(DeliveryRecord record) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO lk_delivery (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, record.getMessageId());
            ps.setString(2, record.getClientId());
            ps.setLong(3, record.getSeq());
            ps.setString(4, record.getState().name());
            ps.setInt(5, record.getRetryCount());
            setNullableLong(ps, 6, record.getNextRetryAt());
            setNullableString(ps, 7, record.getLastError());
            ps.setLong(8, record.getCreatedAt());
            ps.setLong(9, record.getUpdatedAt());
            ps.executeUpdate();
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            // 幂等语义：重复初始化忽略
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "初始化投递记录失败: " + record, e, record.getMessageId());
        }
    }

    @Override
    public Optional<DeliveryRecord> find(String messageId, String clientId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery WHERE message_id = ? AND client_id = ?")) {
            ps.setString(1, messageId);
            ps.setString(2, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRecord(rs)) : Optional.<DeliveryRecord>empty();
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询投递记录失败: " + messageId + "/" + clientId, e, messageId);
        }
    }

    @Override
    public boolean compareAndSetState(String messageId, String clientId,
                                      MessageState expected, MessageState target,
                                      String lastError, Long nextRetryAt,
                                      int expectedRetryCount, int newRetryCount) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "UPDATE lk_delivery SET state = ?, retry_count = ?, next_retry_at = ?, "
                             + "last_error = ?, updated_at = ? "
                             + "WHERE message_id = ? AND client_id = ? AND state = ? AND retry_count = ?")) {
            ps.setString(1, target.name());
            ps.setInt(2, newRetryCount);
            setNullableLong(ps, 3, nextRetryAt);
            setNullableString(ps, 4, lastError);
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, messageId);
            ps.setString(7, clientId);
            ps.setString(8, expected.name());
            ps.setInt(9, expectedRetryCount);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "CAS 更新投递状态失败: " + messageId + "/" + clientId, e, messageId);
        }
    }

    @Override
    public List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE client_id = ? AND seq > ? AND state NOT IN (" + TERMINAL_STATE_LIST + ") "
                             + "ORDER BY seq ASC LIMIT ?")) {
            ps.setString(1, clientId);
            ps.setLong(2, afterSeqExclusive);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询未完成投递失败: " + clientId, e);
        }
    }

    @Override
    public List<DeliveryRecord> findAllByMessageId(String messageId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery WHERE message_id = ? ORDER BY seq ASC")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "按消息查询投递记录失败: " + messageId, e, messageId);
        }
    }

    @Override
    public long countUnfinished(String clientId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM lk_delivery "
                             + "WHERE client_id = ? AND state NOT IN (" + TERMINAL_STATE_LIST + ")")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "统计未完成投递失败: " + clientId, e);
        }
    }

    @Override
    public long nextDeliverySeq(String clientId) {
        try (Connection connection = dataSource.getConnection()) {
            // 仅当连接处于自动提交模式（独立调用）时自管本地事务；
            // 连接已在手动提交模式（宿主事务接管，autoCommit=false）时
            // 直接加入当前事务——严禁中途 commit/rollback，由宿主统一提交/回滚，
            // 否则会在宿主事务内造成"提前提交"，破坏事务性发件箱语义。
            boolean localTx = connection.getAutoCommit();
            if (localTx) {
                connection.setAutoCommit(false);
            }
            try {
                for (int attempt = 1; ; attempt++) {
                    try {
                        long allocated = allocateSeqOnce(connection, clientId);
                        if (localTx) {
                            connection.commit();
                        }
                        return allocated;
                    } catch (SQLException e) {
                        // MySQL/InnoDB 并发首次分配会走"INSERT 重复键 + 间隙锁"路径，
                        // 可能死锁（1213）或锁等待超时（1205）。死锁时 InnoDB 已自动回滚，
                        // 本地事务下显式回滚兜底后换新事务重试（官方推荐做法）；
                        // 宿主事务接管时不可本地重试，直接上抛交由宿主处理。
                        boolean retryable = e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
                        if (retryable && localTx && attempt < 5) {
                            connection.rollback();
                            continue;
                        }
                        throw e;
                    }
                }
            } catch (Exception e) {
                if (localTx) {
                    connection.rollback();
                }
                throw e;
            } finally {
                if (localTx) {
                    connection.setAutoCommit(true);
                }
            }
        } catch (LinkoraException e) {
            throw e;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "分配投递序号失败: " + clientId, e);
        }
    }

    /** 单次分配（调用方在事务内）：确保序号行存在 → 行锁内自增 → 同事务读回。 */
    private long allocateSeqOnce(Connection connection, String clientId) throws SQLException {
        // 1. 确保序号行存在（已存在则忽略，幂等）
        try (PreparedStatement init = connection.prepareStatement(
                "INSERT INTO lk_client_seq (client_id, last_seq) VALUES (?, 0)")) {
            init.setString(1, clientId);
            try {
                init.executeUpdate();
            } catch (SQLIntegrityConstraintViolationException alreadyExists) {
                // 行已存在，继续
            }
        }
        // 2. 行锁内自增：并发分配在此串行化
        try (PreparedStatement increment = connection.prepareStatement(
                "UPDATE lk_client_seq SET last_seq = last_seq + 1 WHERE client_id = ?")) {
            increment.setString(1, clientId);
            if (increment.executeUpdate() != 1) {
                throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                        "分配投递序号失败（序号行更新异常）: " + clientId);
            }
        }
        // 3. 同事务读回分配结果
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT last_seq FROM lk_client_seq WHERE client_id = ?")) {
            select.setString(1, clientId);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                            "分配投递序号失败（序号行读取为空）: " + clientId);
                }
                return rs.getLong(1);
            }
        }
    }

    private static DeliveryRecord mapRecord(ResultSet rs) throws SQLException {
        return new DeliveryRecord(
                rs.getString("message_id"),
                rs.getString("client_id"),
                rs.getLong("seq"),
                MessageState.valueOf(rs.getString("state")),
                rs.getInt("retry_count"),
                getNullableLong(rs, "next_retry_at"),
                rs.getString("last_error"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
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
