package com.linkora.core.storage.server;

/**
 * 服务端保留策略（可选能力）：清理已到终态且超过保留期的投递记录与消息正文。
 *
 * <p>这不是 {@link DeliveryRepository} 的必选语义——自实现仓储可以不实现本接口，
 * 此时服务端只标记终态、不做物理清理（表会持续增长，由宿主自行归档）。
 * 内置 JDBC 实现已实现本接口。</p>
 *
 * <p>清理边界：只清理 {@code PROCESSED} / {@code EXPIRED} 两种终态；
 * {@code DEAD_LETTER} 与所有未完成记录永不清理——前者需要人工介入，
 * 后者是"未送达"的事实依据。</p>
 *
 * @author lianghang
 */
public interface RetentionCleaner {

    /**
     * 清理一批已终结且早于 deadline 的投递记录，以及因此不再被引用的消息正文。
     *
     * <p>实现必须分批（按 {@code batchSize} 限制单次删除量）以规避长事务与大范围锁；
     * 未清理完的部分由下一次扫描继续。</p>
     *
     * @param deadline  早于该时间（epoch 毫秒）的记录可被清理
     * @param batchSize 单次最多清理的投递记录条数
     * @return 实际删除的投递记录条数；0 表示已无待清理数据
     */
    int purgeSettledBefore(long deadline, int batchSize);
}
