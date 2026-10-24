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
     * 查询指定客户端 seq 大于 afterSeqExclusive 的"当前可投递"记录，按 seq 升序。
     *
     * <p>"可投递"= 未到终态、非死信、未到重试时间。死信停止投递但不占批次名额；
     * 未到 {@code next_retry_at} 的记录同样不占名额——两者都通过"查出来再在
     * Java 侧 return"实现的话，会把本批名额白白吃掉，让同客户端后面的可投消息
     * 永远排不进来。</p>
     *
     * @param now 当前时间（毫秒），用于"未到重试时间"的判断；由调用方传入而非
     *            SQL 内取时，保证可测试
     */
    List<DeliveryRecord> findUnfinishedAfterSeq(String clientId, long afterSeqExclusive,
                                                long now, int limit);

    /** 查询一条消息的全部投递记录（通常单目标一条），用于状态查询。 */
    List<DeliveryRecord> findAllByMessageId(String messageId);

    /** 统计指定客户端未完成（未到终态）投递数量，用于积压查询。 */
    long countUnfinished(String clientId);

    /** 为客户端原子分配下一个投递序号（从 1 开始）。 */
    long nextDeliverySeq(String clientId);

    /**
     * 同一 clientId、同一 sequenceKey 下，是否存在 seq 更小且未到终态（且非死信）的记录。
     *
     * <p>用于顺序保证：返回 true 时本条必须让路，等前一条终结后再投。
     * 默认返回 false（不阻塞），自实现仓储不覆盖该方法则退化为"不保证顺序"，
     * 不会破坏兼容。</p>
     *
     * @param sequenceKey 顺序键，null 表示不参与顺序约束（实现应直接返回 false）
     * @param seq         当前记录的投递序号
     */
    default boolean hasEarlierUnsettled(String clientId, String sequenceKey, long seq) {        return false;
    }

    /**
     * 统计同客户端、同顺序键下处于非终态（未到 PROCESSED/EXPIRED）的投递记录数（含自身）。
     *
     * <p>仅供运维界面提示「同键阻塞」（一条卡住、同键后续全部排队），不参与投递主链路。
     * 顺序键为 null/空白时应返回 0；自实现仓储不覆盖该方法也返回 0，仅表现为不提示。</p>
     */
    default int countUnfinishedBySequenceKey(String clientId, String sequenceKey) {
        return 0;
    }

    // ---------------- 管理/观测查询（可选能力） ----------------
    //
    // 三个方法均为 default 实现（空结果），自实现仓储不覆盖不会破坏编译；
    // 仅供管理接口使用，不参与投递主链路。

    /**
     * 查询指定客户端的全部未终结投递记录（含死信，供人工介入），按 seq 升序。
     *
     * <p>与 {@link #findUnfinishedAfterSeq(String, long, long, int)} 不同：
     * 本方法不做"可投递"过滤（不含顺序键门禁与重试时间判断），也不排除死信——
     * 它服务于"看清楚现在压着什么"的管理视角。</p>
     */
    default List<DeliveryRecord> findUnsettled(String clientId, int limit) {
        return java.util.Collections.emptyList();
    }

    /** 查询指定客户端的死信记录（仅人工重试可复活），按更新时间倒序。 */
    default List<DeliveryRecord> findDeadLetters(String clientId, int limit) {
        return java.util.Collections.emptyList();
    }

    /** 统计指定客户端的死信数量。 */
    default long countDeadLetters(String clientId) {
        return 0;
    }

    /**
     * 管理接口：全部投递记录按状态分布（key = 状态名，value = 数量）。
     * default 返回空 Map。
     */
    default java.util.Map<String, Long> countByState() {
        return java.util.Collections.emptyMap();
    }

    /**
     * 清理保留期之外的记录：删除更新时间早于 cutoff 且已到终态
     * （PROCESSED / EXPIRED）的投递记录，以及不再被任何投递记录引用、
     * 创建时间早于 cutoff 的消息正文。
     *
     * <p>豁免：死信（DEAD_LETTER）与非终态记录一律保留——死信等待人工处置，
     * 不得被自动清理。</p>
     *
     * <p>默认空实现（不清理），自实现仓储不升级不破坏编译。</p>
     *
     * @param cutoffEpochMs 保留期截止时间（epoch 毫秒），早于该时间的记录才会被清理
     * @return 删除的总行数（投递记录 + 消息正文）
     */
    default int cleanupRetained(long cutoffEpochMs) {
        return 0;
    }
}
