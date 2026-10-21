package com.linkora.client;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.message.LinkoraMessage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 已接收消息包装：在统一消息模型上提供显式 ACK 语义。
 * ackProcessed / ackFailed 只生效一次（先到先得），重复调用幂等忽略。
 *
 * @author lianghang
 */
public final class LinkoraReceivedMessage {

    private final LinkoraMessage message;
    private final AckSink ackSink;
    private final AtomicBoolean acked = new AtomicBoolean(false);

    public LinkoraReceivedMessage(LinkoraMessage message, AckSink ackSink) {
        if (message == null || ackSink == null) {
            throw new IllegalArgumentException("message 与 ackSink 不能为 null");
        }
        this.message = message;
        this.ackSink = ackSink;
    }

    public LinkoraMessage getMessage() {
        return message;
    }

    public String getMessageId() {
        return message.getMessageId();
    }

    public String getMessageType() {
        return message.getMessageType();
    }

    public byte[] getPayload() {
        return message.getPayload();
    }

    /** 以 UTF-8 解码负载（默认 JSON 场景）。 */
    public String getPayloadAsUtf8String() {
        return message.getPayloadAsUtf8String();
    }

    /** 标记业务处理成功。幂等；之后调用 ackFailed 无效。 */
    public boolean ackProcessed() {
        if (!acked.compareAndSet(false, true)) {
            return false;
        }
        ackSink.onProcessed(message.getMessageId());
        return true;
    }

    /** 标记业务处理失败（带可诊断错误码/信息），按配置重试。幂等；之后调用 ackProcessed 无效。 */
    public boolean ackFailed(LinkoraErrorCode errorCode, String errorMessage) {
        if (!acked.compareAndSet(false, true)) {
            return false;
        }
        ackSink.onFailed(message.getMessageId(),
                errorCode == null ? LinkoraErrorCode.INTERNAL_ERROR : errorCode,
                errorMessage);
        return true;
    }

    /** ACK 回写通道（由客户端实现）。 */
    public interface AckSink {
        void onProcessed(String messageId);

        void onFailed(String messageId, LinkoraErrorCode errorCode, String errorMessage);
    }
}
