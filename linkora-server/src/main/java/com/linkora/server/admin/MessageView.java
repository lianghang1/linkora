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
    /** 聚合投递状态（最需关注的那条投递的状态名，如 RETRYING）。 */
    private final String state;
    /** 聚合投递状态的中文描述。 */
    private final String stateText;
    /** 投递已重试次数（取各投递中的最大值）。 */
    private final int retryCount;
    /** 最近一次投递错误（无错误为 "—"）。 */
    private final String lastError;
    /** 最近投递推进时间（已格式化）。 */
    private final String updatedAt;

    public MessageView(LinkoraMessage message, String deliverySummary, String state,
                       String stateText, int retryCount, String lastError, String updatedAt) {
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
        this.state = state;
        this.stateText = stateText;
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.updatedAt = updatedAt;
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

    /** 聚合投递状态名。 */
    public String getState() {
        return state;
    }

    /** 聚合投递状态中文描述。 */
    public String getStateText() {
        return stateText;
    }

    /** 已重试次数（各投递最大值）。 */
    public int getRetryCount() {
        return retryCount;
    }

    /** 最近一次投递错误（"—" 表示无）。 */
    public String getLastError() {
        return lastError;
    }

    /** 最近投递推进时间（已格式化）。 */
    public String getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "MessageView{messageId='" + messageId + "', type='" + messageType
                + "', createdAt=" + createdAt + "}";
    }
}
