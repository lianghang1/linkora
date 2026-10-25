package com.linkora.server.admin;

import com.linkora.core.message.LinkoraMessage;

import java.util.List;

/**
 * 消息完整详情的管理视图：完整 payload 明文 + 全部投递记录。
 *
 * @author lianghang
 */
public final class MessageDetailView {

    private final String messageId;
    private final String clientId;
    private final String messageType;
    private final String sequenceKey;
    private final String subject;
    private final String createdAt;
    private final String expiresAt;
    private final String traceId;
    /** 完整 payload 明文（UTF-8；二进制内容只标注字节数，不输出乱码）。 */
    private final String payload;
    private final int payloadSize;
    /** 全部投递记录（人类可读）。 */
    private final List<DeliveryView> deliveries;

    public MessageDetailView(LinkoraMessage message, List<DeliveryView> deliveries) {
        this.messageId = message.getMessageId();
        this.clientId = message.getClientId();
        this.messageType = message.getMessageType();
        this.sequenceKey = message.getSequenceKey() == null ? "—" : message.getSequenceKey();
        this.subject = message.getSubject();
        this.createdAt = AdminViews.formatTime(message.getCreatedAt());
        this.expiresAt = AdminViews.formatTime(message.getExpiresAt());
        this.traceId = message.getTraceId() == null ? "—" : message.getTraceId();
        byte[] payload = message.getPayload();
        this.payloadSize = payload.length;
        this.payload = AdminViews.isPrintable(payload)
                ? new String(payload, java.nio.charset.StandardCharsets.UTF_8)
                : "[二进制数据 " + payloadSize + " 字节]";
        this.deliveries = deliveries;
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

    public String getSequenceKey() {
        return sequenceKey;
    }

    public String getSubject() {
        return subject;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public String getTraceId() {
        return traceId;
    }

    /** 完整 payload 明文（二进制只标注字节数）。 */
    public String getPayload() {
        return payload;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public List<DeliveryView> getDeliveries() {
        return deliveries;
    }

    @Override
    public String toString() {
        return "MessageDetailView{messageId='" + messageId + "', deliveries=" + deliveries.size() + "}";
    }
}
