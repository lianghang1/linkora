package com.linkora.core.storage.client;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;

import java.util.List;
import java.util.Optional;

/**
 * 客户端本地存储 SPI（阶段 2 由独立 SQLite 实现）。
 *
 * <p>实现契约：</p>
 * <ul>
 *   <li>messageId 唯一约束：outbox/inbox 重复写入返回 false（幂等去重落点）。</li>
 *   <li>所有状态更新为 CAS 语义（expected 不匹配返回 false），
 *       且必须与消息写入在同一事务内一致。</li>
 *   <li>SQLite 事务未提交前不得触发 ACK（由上层协调器保证，接口注释为契约声明）。</li>
 *   <li>清理方法只删除已完成（终态/已确认）记录，未确认消息不得无条件删除。</li>
 * </ul>
 */
public interface ClientStore {

    // ---------------- outbox：线下 → 线上 ----------------

    /** 幂等入队；messageId 已存在返回 false。 */
    boolean saveOutbox(OutboxEntry entry);

    Optional<OutboxEntry> findOutbox(String messageId);

    /**
     * 查询指定状态、nextRetryAt <= nextRetryBefore 的条目（升序），
     * 用于重试调度与启动恢复。
     */
    List<OutboxEntry> findOutbox(OutboxStatus status, long nextRetryBefore, int limit);

    /** CAS 更新 outbox 条目状态及重试元数据。 */
    boolean updateOutboxStatus(OutboxEntry updated, OutboxStatus expected);

    /** 清理 SERVER_ACCEPTED 且 updatedAt 早于 deadline 的记录，返回删除行数。 */
    int deleteOutboxAcceptedBefore(long deadline);

    // ---------------- inbox：线上 → 线下 ----------------

    /** 幂等写入；messageId 已存在返回 false（接收去重落点）。 */
    boolean saveInbox(InboxEntry entry);

    Optional<InboxEntry> findInbox(String messageId);

    /** 查询需要发送接收 ACK 的条目（RECEIVED_ACK_SENT 之前），按 receivedAt 升序。 */
    List<InboxEntry> findInboxPendingReceiveAck(int limit);

    /** 查询需要业务处理（含重试到期）的条目，按 nextRetryAt/receivedAt 升序。 */
    List<InboxEntry> findInboxByProcessStatus(ProcessStatus status, long nextRetryBefore, int limit);

    /** CAS 更新接收状态。 */
    boolean updateInboxReceiveStatus(InboxEntry updated, ReceiveStatus expected);

    /** CAS 更新处理状态及重试元数据。 */
    boolean updateInboxProcessStatus(InboxEntry updated, ProcessStatus expected);

    /** 清理已处理完成且 updated/received 时间早于 deadline 的记录，返回删除行数。 */
    int deleteInboxProcessedBefore(long deadline);

    // ---------------- meta：游标与本地元数据 ----------------

    void saveMeta(String key, String value);

    Optional<String> getMeta(String key);
}
