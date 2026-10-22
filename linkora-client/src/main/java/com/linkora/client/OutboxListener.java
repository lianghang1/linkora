package com.linkora.client;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.OutboxEntry;

/**
 * Outbox 状态变更监听器：上行消息在本地队列中的状态推进成功后被回调
 * （如 PENDING → SENDING → SERVER_ACCEPTED / FAILED / DEAD_LETTER）。
 *
 * <p>典型用途：业务侧等待"服务端已确认接受"（{@code SERVER_ACCEPTED}），
 * 替代对本地队列的轮询。回调在客户端内部 IO 线程上同步执行，实现方应快速返回，
 * 耗时处理请自行异步化；抛出的异常会被捕获并记录告警，不影响消息状态机。</p>
 *
 * <p>注意：仅状态 CAS 推进成功时回调；初始入队（PENDING 写入）不算状态变更。
 * 客户端进程重启导致的恢复性状态推进（如 SENDING 复位为 PENDING）同样会回调。</p>
 *
 * @author lianghang
 */
public interface OutboxListener {

    /**
     * 一条上行消息的 outbox 状态已成功推进。
     *
     * @param entry 变更后的完整 outbox 条目（状态已是新值）
     * @param previousStatus 变更前的状态
     */
    void onStatusChanged(OutboxEntry entry, OutboxStatus previousStatus);
}
