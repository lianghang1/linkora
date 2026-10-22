package com.linkora.core.storage.server;

/**
 * 服务端过期推进（可选能力）：不依赖客户端在线状态，把已过期的未完成投递标记为 EXPIRED。
 *
 * <p>为什么需要独立于推送路径：{@code DeliveryCoordinator} 的推送与补发只遍历在线客户端，
 * 离线客户端的消息永远不会被"推送时顺手发现过期"。若没有本能力，一个离线一个月的客户端
 * 重连时，几百条早该作废的消息会一次性涌入，{@code getBacklog()} 也会长期虚高。</p>
 *
 * <p>不是 {@link DeliveryRepository} 的必选语义，自实现仓储可不实现（此时过期判定仍会在
 * 重连后的首次推送尝试中发生，只是不及时）。内置 JDBC 实现已实现本接口。</p>
 *
 * @author lianghang
 */
public interface ExpirySweeper {

    /**
     * 把 {@code expires_at < now} 且未到终态的投递记录标记为 EXPIRED。
     *
     * <p>{@code DEAD_LETTER} 不参与——它已经停止投递，与过期无关。</p>
     *
     * @param now       当前时间（epoch 毫秒）
     * @param batchSize 单次最多推进的记录条数，避免一条语句锁住整表
     * @return 本次被标记为 EXPIRED 的记录条数
     */
    int expireOverdue(long now, int batchSize);
}
