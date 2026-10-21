package com.linkora.core.message;

/**
 * 客户端 outbox（线下→线上待发送队列）条目状态。
 * 只有收到服务端可验证的持久化接受结果后才推进到 SERVER_ACCEPTED；
 * 超时/响应丢失不得推进状态，重试必须沿用原 messageId。
 */
public enum OutboxStatus {

    /** 已入队，等待发送。 */
    PENDING,

    /** 正在发送（单飞标记，防止并发重复发送）。 */
    SENDING,

    /** 服务端已持久化接受（终态，可按保留策略清理）。 */
    SERVER_ACCEPTED,

    /** 发送失败，待重试；超过阈值转入死信。 */
    FAILED,

    /** 死信/人工介入。 */
    DEAD_LETTER
}
