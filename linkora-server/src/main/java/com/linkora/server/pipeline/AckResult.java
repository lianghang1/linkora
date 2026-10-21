package com.linkora.server.pipeline;

import com.linkora.core.error.LinkoraErrorCode;

/** ACK 处理结果：OK（含幂等重复与未知消息）或 REJECTED。 */
public final class AckResult {

    private final boolean ok;
    private final LinkoraErrorCode errorCode;
    private final String errorMessage;

    private AckResult(boolean ok, LinkoraErrorCode errorCode, String errorMessage) {
        this.ok = ok;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static AckResult ok() {
        return new AckResult(true, null, null);
    }

    public static AckResult rejected(LinkoraErrorCode errorCode, String errorMessage) {
        return new AckResult(false, errorCode, errorMessage);
    }

    public boolean isOk() {
        return ok;
    }

    public LinkoraErrorCode getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /** 映射 HTTP 状态码。 */
    public int httpStatus() {
        if (ok) {
            return 200;
        }
        if (errorCode == null) {
            return 400;
        }
        switch (errorCode) {
            case AUTH_REQUIRED:
            case AUTH_FAILED:
                return 401;
            case FORBIDDEN:
                return 403;
            case UNSUPPORTED_PROTOCOL:
                return 412;
            default:
                return 400;
        }
    }
}
