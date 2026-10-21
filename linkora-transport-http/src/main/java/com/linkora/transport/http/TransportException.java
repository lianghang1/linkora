package com.linkora.transport.http;

/**
 * 传输层异常。携带错误分类、HTTP 状态码与服务端 Retry-After 提示，
 * 供上层重试调度使用。本层不做任何自动重试。
 *
 * @author lianghang
 */
public class TransportException extends RuntimeException {

    private final TransportErrorType errorType;
    /** HTTP 状态码；非 HTTP 响应类失败为 null。 */
    private final Integer httpCode;
    /** 服务端 Retry-After 提示（毫秒）；仅秒数字格式可解析，其余为 null。 */
    private final Long retryAfterMs;

    public TransportException(TransportErrorType errorType, String message) {
        this(errorType, message, null, null, null);
    }

    public TransportException(TransportErrorType errorType, String message, Throwable cause) {
        this(errorType, message, cause, null, null);
    }

    public TransportException(TransportErrorType errorType, String message,
                              Throwable cause, Integer httpCode, Long retryAfterMs) {
        super(message, cause);
        if (errorType == null) {
            throw new IllegalArgumentException("errorType 不能为 null");
        }
        this.errorType = errorType;
        this.httpCode = httpCode;
        this.retryAfterMs = retryAfterMs;
    }

    public TransportErrorType getErrorType() {
        return errorType;
    }

    public Integer getHttpCode() {
        return httpCode;
    }

    public Long getRetryAfterMs() {
        return retryAfterMs;
    }

    /** 是否可重试。类型默认值可由异常创建时覆盖（当前实现未覆盖，保留扩展点）。 */
    public boolean isRetryable() {
        return errorType.isRetryableDefault();
    }

    static TransportException networkError(String message, Throwable cause) {
        return new TransportException(TransportErrorType.NETWORK_ERROR, message, cause);
    }

    static TransportException timeout(String message, Throwable cause) {
        return new TransportException(TransportErrorType.TIMEOUT, message, cause);
    }

    static TransportException interrupted(String message, Throwable cause) {
        return new TransportException(TransportErrorType.INTERRUPTED, message, cause);
    }

    static TransportException httpError(int httpCode, Long retryAfterMs) {
        TransportErrorType type = classifyHttpCode(httpCode);
        return new TransportException(type, "HTTP " + httpCode + "（" + type + "）",
                null, httpCode, retryAfterMs);
    }

    /**
     * 将非 2xx 的 {@link TransportResponse} 转换为携带分类信息的异常。
     * 响应体（可能包含服务端错误码）应在上层转换前先读取保留。
     */
    public static TransportException fromResponse(TransportResponse response) {
        return httpError(response.getCode(), parseRetryAfterSeconds(response.header("Retry-After")));
    }

    /** 解析 Retry-After 的纯秒数格式；其它格式（如 HTTP 日期）返回 null。 */
    public static Long parseRetryAfterSeconds(String retryAfterHeader) {
        if (retryAfterHeader == null || retryAfterHeader.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(retryAfterHeader.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static TransportException responseTooLarge(long limitBytes) {
        return new TransportException(TransportErrorType.RESPONSE_TOO_LARGE,
                "响应体超过大小上限 " + limitBytes + " 字节");
    }

    static TransportErrorType classifyHttpCode(int code) {
        if (code == 429) {
            return TransportErrorType.RATE_LIMITED;
        }
        if (code == 408) {
            return TransportErrorType.TIMEOUT;
        }
        if (code >= 200 && code < 300) {
            throw new IllegalArgumentException("2xx 不属于传输错误: " + code);
        }
        if (code >= 400 && code < 500) {
            return TransportErrorType.CLIENT_ERROR;
        }
        if (code >= 500 && code < 600) {
            return TransportErrorType.SERVER_ERROR;
        }
        return TransportErrorType.CLIENT_ERROR;
    }
}
