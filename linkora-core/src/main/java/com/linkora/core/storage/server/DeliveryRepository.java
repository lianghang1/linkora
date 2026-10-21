package com.linkora.core.storage.server;

import com.linkora.core.message.MessageState;

import java.util.List;
import java.util.Optional;

/**
 * 服务端投递状态仓储（消息 × 客户端 维度）。
 *
 * <p>实现必须保证：</p>
 * <ul>
 *   <li>(messageId, clientId) 唯一约束。</li>
 *   <li>状态更新采用 CAS 语义：仅当当前状态等于 expected 时才写入 target，
 *       竞争失败返回 false，由调用方基于最新状态重放决策——
 *       这是"旧请求不得回退已完成状态"的落点。</li>
 *   <li>seq 为每个客户端独立递增的投递序号，分配必须原子。</li>
 * </ul>
 */
public interface DeliveryRepository {

    /** 初始化投递记录；重复初始化幂等忽略。 */
    void init(DeliveryRecord record);

    Optional<DeliveryRecord> find(String messageId, String clientId);

    /**
     * CAS 状态更新。
     *
     * @param expected   期望的当前状态
     * @param target     目标状态（合法性由调用方通过 MessageState 校验）
     * @param lastError  可诊断错误信息，可为 null
     * @param nextRetryAt 下次重试时间，可为 null
     * @return true 表示写入成功；false 表示当前状态不等于 expected
     */
    boolean compareAndSetState(String messageId, String clientId,
                               MessageState expected, MessageState target,
                               String lastError, Long nextRetryAt, int retryCount);

    /**
     * 查询指定客户端 seq 大于 afterSeqExclusive 的未完成消息（未到终态），
     * 按 seq 升序返回，用于重连补发。
     */
    List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive, int limit);

    /** 查询一条消息的全部投递记录（通常单目标一条），用于状态查询。 */
    List<DeliveryRecord> findAllByMessageId(String messageId);

    /** 统计指定客户端未完成（未到终态）投递数量，用于积压查询。 */
    long countUnfinished(String clientId);

    /** 为客户端原子分配下一个投递序号（从 1 开始）。 */
    long nextDeliverySeq(String clientId);
}
