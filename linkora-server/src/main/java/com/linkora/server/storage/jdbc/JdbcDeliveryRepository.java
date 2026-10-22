package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.ExpirySweeper;
import com.linkora.core.storage.server.RetentionCleaner;

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
 *   <li>同时实现 {@link RetentionCleaner} 与 {@link ExpirySweeper}：保留策略与
 *       过期推进都落在投递表上，由协调器的周期扫描调用。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class JdbcDeliveryRepository implements DeliveryRepository,
        RetentionCleaner, ExpirySweeper {

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
    public List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive,
                                                       long now, int limit) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM lk_delivery "
                             + "WHERE client_id = ? AND seq > ? "
                             // 终态不再投递
                             + "AND state NOT IN (" + TERMINAL_STATE_LIST + ") "
                             // 死信已停止投递，等人工介入：绝不能占补发批次的名额
                             + "AND state <> '" + MessageState.DEAD_LETTER.name() + "' "
                             // 未到重试时间的记录本轮不取，否则会反复占位并空转
                             + "AND (next_retry_at IS NULL OR next_retry_at <= ?) "
                             // 顺序键门禁：同 key 更早的记录还没终结（且不是死信）时让路，
                             // 保证同一实体（订单/账户/设备…）的状态严格按发布顺序到达
                             + "AND NOT EXISTS ("
                             + "  SELECT 1 FROM lk_delivery p"
                             + "  WHERE p.client_id = lk_delivery.client_id"
                             + "    AND p.sequence_key = lk_delivery.sequence_key"
                             + "    AND p.sequence_key IS NOT NULL"
                             + "    AND p.seq < lk_delivery.seq"
                             + "    AND p.state NOT IN (" + TERMINAL_STATE_LIST + ")"
                             + "    AND p.state <> '" + MessageState.DEAD_LETTER.name() + "'"
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

    @Override
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
            // 顺序判断失败不应该阻断投递：退化为"允许投递"，由客户端幂等兜底
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询顺序键前序记录失败: " + clientId + "/" + sequenceKey, e);
        }
    }

    // ---------------- 保留策略与过期推进（可选能力） ----------------

    /** 仍需投递的非终态集合（DEAD_LETTER 已停止投递，不参与过期推进）。 */
    private static final String UNFINISHED_STATE_LIST =
            "'CREATED', 'ACCEPTED', 'RECEIVED', 'FAILED', 'RETRYING'";

    /**
     * 不依赖客户端在线状态推进过期：EXISTS 关联 lk_message.expires_at，
     * 只动未终结的记录；先取主键再批量更新，避免一条 UPDATE 扫全表。
     */
    @Override
    public int expireOverdue(long now, int batchSize) {
        List<String[]> keys = selectKeys(
                "SELECT d.message_id, d.client_id FROM lk_delivery d "
                        + "WHERE d.state IN (" + UNFINISHED_STATE_LIST + ") "
                        + "AND EXISTS (SELECT 1 FROM lk_message m WHERE m.message_id = d.message_id "
                        + "AND m.expires_at IS NOT NULL AND m.expires_at < ?) "
                        + "LIMIT ?",
                now, batchSize, "查询待过期投递记录失败");
        if (keys.isEmpty()) {
            return 0;
        }
        return batchUpdate(
                "UPDATE lk_delivery SET state = ?, last_error = ?, updated_at = ? "
                        + "WHERE message_id = ? AND client_id = ? "
                        + "AND state IN (" + UNFINISHED_STATE_LIST + ")",
                MessageState.EXPIRED, "消息已过期", now, keys, "标记投递过期失败");
    }

    /**
     * 清理已终结且超过保留期的投递记录，并回收不再被引用的消息正文。
     *
     * <p>DEAD_LETTER 永不清理——它是"需要人工介入"的唯一凭据。</p>
     */
    @Override
    public int purgeSettledBefore(long deadline, int batchSize) {
        List<String[]> settled = selectKeys(
                "SELECT message_id, client_id FROM lk_delivery "
                        + "WHERE state IN (" + TERMINAL_STATE_LIST + ") AND updated_at < ? "
                        + "ORDER BY updated_at ASC LIMIT ?",
                deadline, batchSize, "查询待清理投递记录失败");
        if (settled.isEmpty()) {
            return 0;
        }
        int deleted = batchDelete(
                "DELETE FROM lk_delivery WHERE message_id = ? AND client_id = ? "
                        + "AND state IN (" + TERMINAL_STATE_LIST + ")",
                settled, "清理投递记录失败");
        purgeOrphanMessages(deadline, batchSize);
        return deleted;
    }

    /**
     * 删除不再被任何投递记录引用的消息正文。
     *
     * <p>先查主键再删：MySQL 不允许在子查询里引用被更新的同一张表（错误 1093），
     * 先取 id 列表是唯一两端通吃的写法。</p>
     */
    private void purgeOrphanMessages(long deadline, int batchSize) {
        List<String> messageIds = new ArrayList<String>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT m.message_id FROM lk_message m WHERE m.created_at < ? "
                             + "AND NOT EXISTS (SELECT 1 FROM lk_delivery d WHERE d.message_id = m.message_id) "
                             + "ORDER BY m.created_at ASC LIMIT ?")) {
            ps.setLong(1, deadline);
            ps.setInt(2, batchSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    messageIds.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "查询孤儿消息失败", e);
        }
        if (messageIds.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "DELETE FROM lk_message WHERE message_id = ?")) {
            for (String messageId : messageIds) {
                ps.setString(1, messageId);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "清理消息正文失败", e);
        }
    }

    /** 按时间上界 + 条数上限取待处理主键（(messageId, clientId) 二元组）。 */
    private List<String[]> selectKeys(String sql, long timeBound, int limit, String errorMessage) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, timeBound);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<String[]> keys = new ArrayList<String[]>();
                while (rs.next()) {
                    keys.add(new String[]{rs.getString(1), rs.getString(2)});
                }
                return keys;
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR, errorMessage, e);
        }
    }

    private int batchUpdate(String sql, MessageState state, String lastError, long now,
                            List<String[]> keys, String errorMessage) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (String[] key : keys) {
                ps.setString(1, state.name());
                ps.setString(2, lastError);
                ps.setLong(3, now);
                ps.setString(4, key[0]);
                ps.setString(5, key[1]);
                ps.addBatch();
            }
            int affected = 0;
            for (int count : ps.executeBatch()) {
                if (count > 0) {
                    affected += count;
                }
            }
            return affected;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR, errorMessage, e);
        }
    }

    private int batchDelete(String sql, List<String[]> keys, String errorMessage) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (String[] key : keys) {
                ps.setString(1, key[0]);
                ps.setString(2, key[1]);
                ps.addBatch();
            }
            int affected = 0;
            for (int count : ps.executeBatch()) {
                if (count > 0) {
                    affected += count;
                }
            }
            return affected;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR, errorMessage, e);
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
