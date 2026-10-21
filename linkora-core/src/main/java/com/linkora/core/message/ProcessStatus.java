package com.linkora.core.message;

/**
 * 客户端 inbox 的业务处理进度。
 * 业务处理结果独立于接收确认：RECEIVED ACK ≠ 业务处理成功。
 */
public enum ProcessStatus {

    /** 等待业务监听器处理。 */
    PENDING,

    /** 业务监听器正在处理。 */
    PROCESSING,

    /** 业务处理成功。 */
    PROCESSED,

    /** 业务处理失败（将按配置重试，超过阈值进入死信）。 */
    FAILED,

    /** 待重试。 */
    RETRYING
}
