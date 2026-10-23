package com.linkora.server.admin;

import com.linkora.core.message.LinkoraMessage;

/**
 * 消息列表条目的管理视图（人类可读）：时间为格式化字符串，
 * payload 为 UTF-8 文本预览，附带各目标投递状态摘要。
 *
 * @author lianghang
 */
public final class MessageView {

    private final String messageId;
    private final String clientId;
    private final String messageType;
    private final String sequenceKey;
    private final String createdAt;
    private final String expiresAt;
    private final String payloadPreview;
    private final int payloadSize;
    private final String traceId;
    /** 各目标投递状态摘要，形如 "store-001:已接受，等待推送"；多目标以 "；" 分隔。 */
    private final String deliverySummary;

    public MessageView(LinkoraMessage message, String deliverySummary) {
        this.messageId = message.getMessageId();
        this.clientId = message.getClientId();
        this.messageType = message.getMessageType();
        this.sequenceKey = message.getSequenceKey() == null ? "—" : message.getSequenceKey();
        this.createdAt = AdminViews.formatTime(message.getCreatedAt());
        this.expiresAt = AdminViews.formatTime(message.getExpiresAt());
        this.payloadPreview = AdminViews.payloadPreview(message.getPayload());
        this.payloadSize = message.getPayloadSize();
        this.traceId = message.getTraceId() == null ? "—" : message.getTraceId();
        this.deliverySummary = deliverySummary;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getMessageType() {
        return messageType;
    }

    /** 顺序键（"—" 表示无键）。 */
    public String getSequenceKey() {
        return sequenceKey;
    }

    /** 发布时间（已格式化）。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 过期时间（已格式化；"—" 表示不过期）。 */
    public String getExpiresAt() {
        return expiresAt;
    }

    /** payload 的 UTF-8 文本预览（超长截断；二进制只标注字节数）。 */
    public String getPayloadPreview() {
        return payloadPreview;
    }

    /** payload 原始字节数。 */
    public int getPayloadSize() {
        return payloadSize;
    }

    /** 链路追踪 ID（"—" 表示无）。 */
    public String getTraceId() {
        return traceId;
    }

    /** 各目标投递状态摘要。 */
    public String getDeliverySummary() {
        return deliverySummary;
    }

    @Override
    public String toString() {
        return "MessageView{messageId='" + messageId + "', type='" + messageType
                + "', createdAt=" + createdAt + "}";
    }
}
