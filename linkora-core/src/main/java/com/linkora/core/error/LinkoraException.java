package com.linkora.core.error;

/**
 * Linkora 统一异常。携带稳定错误码，便于业务方按码处理。
 */
public class LinkoraException extends RuntimeException {

    private final LinkoraErrorCode errorCode;
    private final String messageId;

    public LinkoraException(LinkoraErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public LinkoraException(LinkoraErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, cause, null);
    }

    public LinkoraException(LinkoraErrorCode errorCode, String message,
                            Throwable cause, String messageId) {
        super(message, cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode 不能为 null");
        }
        this.errorCode = errorCode;
        this.messageId = messageId;
    }

    public LinkoraErrorCode getErrorCode() {
        return errorCode;
    }

    /** 关联的消息 ID，可为 null。 */
    public String getMessageId() {
        return messageId;
    }
}
