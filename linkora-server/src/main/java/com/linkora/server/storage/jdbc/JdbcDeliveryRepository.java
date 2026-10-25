package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;

import com.linkora.core.storage.jdbc.JdbcErrors;
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
            "message_id, client_id, seq, state, retry_count, next_retry_at, last_error, "
                    + "created_at, updated_at, sequence_key";

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
                     "INSERT INTO lk_delivery (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, record.getMessageId());
            ps.setString(2, record.getClientId());
            ps.setLong(3, record.getSeq());
            ps.setString(4, record.getState().name());
            ps.setInt(5, record.getRetryCount());
            setNullableLong(ps, 6, record.getNextRetryAt());
            setNullableString(ps, 7, record.getLastError());
            ps.setLong(8, record.getCreatedAt());
            ps.setLong(9, record.getUpdatedAt());
            setNullableString(ps, 10, record.getSequenceKey());
            ps.executeUpdate();
        } catch (SQLException e) {
            // 幂等语义：重复初始化忽略；其余错误照常上抛
            if (!JdbcErrors.isIntegrityViolation(e)) {
                throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                        "初始化投递记录失败: " + record, e, record.getMessageId());
            }
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
    public List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive,
                                                       long now, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE client_id = ? AND seq > ? AND state NOT IN (" + TERMINAL_STATE_LIST + ") "
                             // 死信停止投递，但不占批次名额；否则前 N 条全死信时
                             // 同客户端后面的可投消息永远进不了这一批
                             + "AND state <> '" + MessageState.DEAD_LETTER.name() + "' "
                             // 未到重试时间的记录同样不占名额，本轮跳过
                             + "AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                             // 顺序键门禁：同 key 更早的记录还没终结（且非死信）时让路，
                             // 保证同一业务实体（订单/账户/设备…）严格按发布顺序到达。
                             // 放在 SQL 而不是查出来再在 Java 侧 return，
                             // 否则被挡的消息会占批次名额（重演"死信占位"那类队头阻塞）
                             + "AND NOT EXISTS ("
                             + "SELECT 1 FROM lk_delivery p"
                             + " WHERE p.client_id = lk_delivery.client_id"
                             + " AND p.sequence_key = lk_delivery.sequence_key"
                             + " AND p.sequence_key IS NOT NULL"
                             + " AND p.seq < lk_delivery.seq"
                             + " AND p.state NOT IN (" + TERMINAL_STATE_LIST + ")"
                             + " AND p.state <> '" + MessageState.DEAD_LETTER.name() + "'"
                             + ") "
                             + "ORDER BY seq ASC LIMIT ?")) {
            ps.setString(1, clientId);
            ps.setLong(2, afterSeqExclusive);
            ps.setLong(3, now);
            ps.setInt(4, limit);
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
            // 种子行确保标记：首次尝试会 INSERT 种子行；重试时行必然已存在（并发方创建），
            // 跳过 INSERT 直接 UPDATE——避免反复触发"INSERT 重复键间隙锁 + UPDATE"
            // 的 InnoDB 死锁组合（纯 UPDATE 走单行锁等待队列，不会死锁）
            boolean seqRowEnsured = false;
            try {
                for (int attempt = 1; ; attempt++) {
                    try {
                        long allocated = allocateSeqOnce(connection, clientId, seqRowEnsured);
                        if (localTx) {
                            connection.commit();
                        }
                        return allocated;
                    } catch (SQLException e) {
                        // MySQL/InnoDB 并发首次分配可能死锁（1213）或锁等待超时（1205）。
                        // 死锁时 InnoDB 已自动回滚，本地事务下显式回滚兜底后换新事务重试
                        // （官方推荐做法）；宿主事务接管时不可本地重试，直接上抛交由宿主处理。
                        boolean retryable = e.getErrorCode() == 1213 || e.getErrorCode() == 1205;
                        if (retryable && localTx && attempt < 8) {
                            connection.rollback();
                            seqRowEnsured = true;   // 死锁说明并发方已插入种子行，重试不再 INSERT
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
    private long allocateSeqOnce(Connection connection, String clientId,
                                 boolean seqRowEnsured) throws SQLException {
        // 1. 确保序号行存在（已存在则忽略，幂等）；重试时跳过（见 nextDeliverySeq 重试逻辑）
        if (!seqRowEnsured) {
            try (PreparedStatement init = connection.prepareStatement(
                    "INSERT INTO lk_client_seq (client_id, last_seq) VALUES (?, 0)")) {
                init.setString(1, clientId);
                try {
                    init.executeUpdate();
                } catch (SQLException alreadyExists) {
                if (!JdbcErrors.isIntegrityViolation(alreadyExists)) {
                    throw alreadyExists;
                }
                    // 行已存在，继续
                }
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

    @Override
    public int countUnfinishedBySequenceKey(String clientId, String sequenceKey) {
        if (sequenceKey == null || sequenceKey.trim().isEmpty()) {
            return 0;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM lk_delivery "
                             + "WHERE client_id = ? AND sequence_key = ? "
                             + "AND state NOT IN ('" + MessageState.PROCESSED.name() + "', '"
                             + MessageState.EXPIRED.name() + "')")) {
            ps.setString(1, clientId);
            ps.setString(2, sequenceKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "统计同键未完成记录失败: " + clientId + "/" + sequenceKey, e);
        }
    }

    public boolean hasEarlierUnsettled(String clientId, String sequenceKey, long seq) {
        if (sequenceKey == null || sequenceKey.trim().isEmpty()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT 1 FROM lk_delivery "
                             + "WHERE client_id = ? AND sequence_key = ? AND seq < ? "
                             + "AND state NOT IN (" + TERMINAL_STATE_LIST + ") "
                             + "AND state <> '" + MessageState.DEAD_LETTER.name() + "' "
                             + "LIMIT 1")) {
            ps.setString(1, clientId);
            ps.setString(2, sequenceKey);
            ps.setLong(3, seq);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // 顺序判断失败按存储错误抛出：调用方（attemptPush）捕获后跳过本条，
            // 下一轮扫描重来——实际行为是"暂不投递"，不是"降级放行"
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询顺序键前序记录失败: " + clientId + "/" + sequenceKey, e);
        }
    }

    @Override
    public List<DeliveryRecord> findUnsettled(String clientId, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE client_id = ? AND state NOT IN (" + TERMINAL_STATE_LIST + ") "
                             + "ORDER BY seq ASC LIMIT ?")) {
            ps.setString(1, clientId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询未终结投递失败: " + clientId, e);
        }
    }

    @Override
    public List<DeliveryRecord> findDeadLetters(String clientId, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE client_id = ? AND state = '" + MessageState.DEAD_LETTER.name() + "' "
                             + "ORDER BY updated_at DESC LIMIT ?")) {
            ps.setString(1, clientId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询死信失败: " + clientId, e);
        }
    }

    @Override
    public long countDeadLetters(String clientId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT COUNT(*) FROM lk_delivery "
                             + "WHERE client_id = ? AND state = '" + MessageState.DEAD_LETTER.name() + "'")) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "统计死信失败: " + clientId, e);
        }
    }

    @Override
    public List<DeliveryRecord> findDeadLettersSince(long sinceUpdated, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE state = '" + MessageState.DEAD_LETTER.name() + "' "
                             + "AND updated_at > ? ORDER BY updated_at DESC LIMIT ?")) {
            ps.setLong(1, sinceUpdated);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "增量查询死信失败", e);
        }
    }

    @Override
    public List<DeliveryRecord> findStuckBefore(long threshold, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE state IN ('" + MessageState.CREATED.name() + "', '"
                             + MessageState.ACCEPTED.name() + "', '" + MessageState.RECEIVED.name() + "') "
                             + "AND updated_at <= ? ORDER BY updated_at ASC LIMIT ?")) {
            ps.setLong(1, threshold);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<DeliveryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapRecord(rs));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询长时间无推进的投递失败", e);
        }
    }

    @Override
    public java.util.Map<String, Long> countByState() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT state, COUNT(*) FROM lk_delivery GROUP BY state");
             ResultSet rs = ps.executeQuery()) {
            java.util.Map<String, Long> result = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getLong(2));
            }
            return result;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "统计投递状态分布失败", e);
        }
    }

    /** 单批清理的行数上限：控制单条 DELETE 规模，避免长事务与锁放大。 */
    private static final int CLEANUP_BATCH = 500;
    private static final String TERMINAL_FOR_CLEANUP =
            MessageState.PROCESSED.name() + "','" + MessageState.EXPIRED.name();

    @Override
    public int cleanupRetained(long cutoffEpochMs) {
        int total = 0;
        total += cleanupDeliveryRows(cutoffEpochMs);
        total += cleanupOrphanMessages(cutoffEpochMs);
        return total;
    }

    /** 分批删除"已终态（PROCESSED/EXPIRED）且超期"的投递记录；死信与非终态天然不在范围内。 */
    private int cleanupDeliveryRows(long cutoff) {
        int total = 0;
        while (true) {
            List<String> ids = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT message_id FROM lk_delivery WHERE state IN ('" + TERMINAL_FOR_CLEANUP
                                 + "') AND updated_at < ? LIMIT " + CLEANUP_BATCH)) {
                select.setLong(1, cutoff);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                        "保留期清理：查询超期投递记录失败", e);
            }
            if (ids.isEmpty()) {
                return total;
            }
            total += deleteByIds("lk_delivery", "message_id", ids);
        }
    }

    /** 分批删除"无任何投递记录引用且超期"的消息正文（上行消息与已清完投递的下行消息）。 */
    private int cleanupOrphanMessages(long cutoff) {
        int total = 0;
        while (true) {
            List<String> ids = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement select = connection.prepareStatement(
                         "SELECT message_id FROM lk_message m WHERE m.created_at < ? "
                                 + "AND NOT EXISTS (SELECT 1 FROM lk_delivery d "
                                 + "WHERE d.message_id = m.message_id) LIMIT " + CLEANUP_BATCH)) {
                select.setLong(1, cutoff);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getString(1));
                    }
                }
            } catch (SQLException e) {
                throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                        "保留期清理：查询超期消息正文失败", e);
            }
            if (ids.isEmpty()) {
                return total;
            }
            total += deleteByIds("lk_message", "message_id", ids);
        }
    }

    private int deleteByIds(String table, String column, List<String> ids) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            placeholders.append(i == 0 ? "?" : ",?");
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE " + column
                             + " IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) {
                delete.setString(i + 1, ids.get(i));
            }
            return delete.executeUpdate();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "保留期清理：删除 " + table + " 失败", e);
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
                rs.getLong("updated_at"),
                rs.getString("sequence_key"));
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
