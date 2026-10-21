package com.linkora.transport.http;

/**
 * 传输层错误分类。传输层本身不执行重试，只负责把失败归类，
 * 由上层（客户端发送调度）依据 {@link #isRetryableDefault()} 决定是否重试。
 */
public enum TransportErrorType {

    /** 网络层错误（连接失败、DNS、SSL、连接被重置等），默认可重试。 */
    NETWORK_ERROR(true),

    /** 读写超时（含 HTTP 408），默认可重试。注意：超时 ≠ 服务端一定没有写入。 */
    TIMEOUT(true),

    /** 线程被中断，默认不重试（由调用方决定恢复语义）。 */
    INTERRUPTED(false),

    /** 4xx 客户端错误（不含 408/429），默认不可重试——重试同样会失败。 */
    CLIENT_ERROR(false),

    /** 5xx 服务端错误，默认可重试。 */
    SERVER_ERROR(true),

    /** 429 限流，默认可重试（应按 retryAfterMs 退避）。 */
    RATE_LIMITED(true),

    /** 响应体超过配置的大小上限，默认不可重试。 */
    RESPONSE_TOO_LARGE(false);

    private final boolean retryableDefault;

    TransportErrorType(boolean retryableDefault) {
        this.retryableDefault = retryableDefault;
    }

    /** 该类型错误的默认可重试判定。 */
    public boolean isRetryableDefault() {
        return retryableDefault;
    }
}
