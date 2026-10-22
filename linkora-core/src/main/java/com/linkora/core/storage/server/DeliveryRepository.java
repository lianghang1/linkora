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
 *
 * @author lianghang
 */
public interface DeliveryRepository {

    /** 初始化投递记录；重复初始化幂等忽略。 */
    void init(DeliveryRecord record);

    Optional<DeliveryRecord> find(String messageId, String clientId);

    /**
     * CAS 状态更新。期望状态与期望 retry_count 必须同时匹配才写入，
     * 防止过期快照无条件覆盖并发推进后的 retry_count（如迟到 ACK 回退失败计数）。
     *
     * @param expected           期望的当前状态
     * @param target             目标状态（合法性由调用方通过 MessageState 校验）
     * @param lastError          可诊断错误信息，可为 null
     * @param nextRetryAt        下次重试时间，可为 null
     * @param expectedRetryCount 期望的当前失败计数
     * @param newRetryCount      写入的新失败计数
     * @return true 表示写入成功；false 表示当前状态或 retry_count 不等于期望
     */
    boolean compareAndSetState(String messageId, String clientId,
                               MessageState expected, MessageState target,
                               String lastError, Long nextRetryAt,
                               int expectedRetryCount, int newRetryCount);

    /**
     * 查询指定客户端 seq 大于 afterSeqExclusive 且<b>当前可投递</b>的记录，
     * 按 seq 升序返回，用于重连补发与周期扫描。
     *
     * <p>"当前可投递"必须排除三类记录，否则它们会占满批次名额、让后面的活消息饿死：</p>
     * <ul>
     *   <li>已到终态（PROCESSED / EXPIRED）；</li>
     *   <li>死信（DEAD_LETTER）——已停止投递，等人工介入，不应占用补发配额；</li>
     *   <li>尚未到重试时间的记录（next_retry_at 晚于 now）。</li>
     * </ul>
     *
     * @param now 当前时间（epoch 毫秒），用于判断重试是否到期
     */
    List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive,
                                                long now, int limit);

    /**
     * 兼容旧签名：等价于 {@code findUnfinishedAfterSeq(clientId, afterSeqExclusive, now, limit)}。
     *
     * @deprecated 语义上必须知道"当前时间"才能过滤未到期的重试记录，
     *             请实现带 {@code now} 的版本；本方法保留仅为避免既有调用方立即编译失败。
     */
    @Deprecated
    default List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive,
                                                        int limit) {
        return findUnfinishedAfterSeq(clientId, afterSeqExclusive,
                System.currentTimeMillis(), limit);
    }

    /** 查询一条消息的全部投递记录（通常单目标一条），用于状态查询。 */
    List<DeliveryRecord> findAllByMessageId(String messageId);

    /** 统计指定客户端未完成（未到终态）投递数量，用于积压查询。 */
    long countUnfinished(String clientId);

    /** 为客户端原子分配下一个投递序号（从 1 开始）。 */
    long nextDeliverySeq(String clientId);
}
