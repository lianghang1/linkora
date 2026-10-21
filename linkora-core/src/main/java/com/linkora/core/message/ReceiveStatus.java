package com.linkora.core.message;

/**
 * 客户端 inbox 的接收确认进度。
 * 只有 SQLite 事务提交成功后才允许从 PERSISTED 推进到 RECEIVED_ACK_SENT。
 */
public enum ReceiveStatus {

    /** 已写入本地 SQLite，尚未发送接收 ACK。 */
    PERSISTED,

    /** 接收 ACK 已发送。 */
    RECEIVED_ACK_SENT
}
