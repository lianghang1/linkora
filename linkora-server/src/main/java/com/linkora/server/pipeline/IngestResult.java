package com.linkora.server.pipeline;

import com.linkora.core.error.LinkoraErrorCode;

/** 消息摄入（上行上报）处理结果。
 *
 * @author lianghang
 */
public final class IngestResult {

    public enum Status { ACCEPTED, DUPLICATE, REJECTED, PROCESSING_FAILED }

    private final Status status;
    private final String messageId;
    private final LinkoraErrorCode errorCode;
    private final String errorMessage;

    private IngestResult(Status status, String messageId,
                         LinkoraErrorCode errorCode, String errorMessage) {
        this.status = status;
        this.messageId = messageId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static IngestResult accepted(String messageId) {
        return new IngestResult(Status.ACCEPTED, messageId, null, null);
    }

    public static IngestResult duplicate(String messageId) {
        return new IngestResult(Status.DUPLICATE, messageId, null, null);
    }

    /**
     * 业务处理失败：消息已入库（state=FAILED），客户端收到 422 后
     * 按不可重试语义进入本地死信，由运维修复后经死信重放恢复。
     */
    public static IngestResult processingFailed(String messageId, String errorMessage) {
        return new IngestResult(Status.PROCESSING_FAILED, messageId,
                LinkoraErrorCode.PROCESSING_FAILED, errorMessage);
    }

    public static IngestResult rejected(LinkoraErrorCode errorCode, String errorMessage) {
        return new IngestResult(Status.REJECTED, null, errorCode, errorMessage);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessageId() {
        return messageId;
    }

    public LinkoraErrorCode getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    /** 映射 HTTP 状态码：拒绝类按错误码区分，接受类一律 200，处理失败 422。 */
    public int httpStatus() {
        switch (status) {
            case ACCEPTED:
            case DUPLICATE:
                return 200;
            case PROCESSING_FAILED:
                return 422;
            default:
                if (errorCode == null) {
                    return 400;
                }
                switch (errorCode) {
                    case AUTH_REQUIRED:
                    case AUTH_FAILED:
                        return 401;
                    case FORBIDDEN:
                        return 403;
                    case MESSAGE_TOO_LARGE:
                        return 413;
                    case RATE_LIMITED:
                        return 429;
                    case UNSUPPORTED_PROTOCOL:
                        return 412;
                    default:
                        return 400;
                }
        }
    }
}
