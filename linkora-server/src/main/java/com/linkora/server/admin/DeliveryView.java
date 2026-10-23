package com.linkora.server.admin;

import com.linkora.core.message.MessageState;

/**
 * 投递记录的管理视图（人类可读）：时间为 {@code yyyy-MM-dd HH:mm:ss}，
 * payload 为 UTF-8 文本预览，绝不输出 Base64 或裸时间戳。
 *
 * @author lianghang
 */
public final class DeliveryView {

    private final String messageId;
    private final String clientId;
    private final String messageType;
    private final long seq;
    private final MessageState state;
    private final String stateText;
    private final int retryCount;
    private final String nextRetryAt;
    private final String lastError;
    private final String payloadPreview;
    private final int payloadSize;
    private final String createdAt;
    private final String updatedAt;

    public DeliveryView(String messageId, String clientId, String messageType, long seq,
                        MessageState state, int retryCount, Long nextRetryAt, String lastError,
                        byte[] payload, long createdAt, long updatedAt) {
        this.messageId = messageId;
        this.clientId = clientId;
        this.messageType = messageType;
        this.seq = seq;
        this.state = state;
        this.stateText = AdminViews.stateText(state);
        this.retryCount = retryCount;
        this.nextRetryAt = AdminViews.formatTime(nextRetryAt);
        this.lastError = lastError == null ? "—" : lastError;
        this.payloadPreview = AdminViews.payloadPreview(payload);
        this.payloadSize = payload == null ? 0 : payload.length;
        this.createdAt = AdminViews.formatTime(createdAt);
        this.updatedAt = AdminViews.formatTime(updatedAt);
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

    /** 投递序号（Last-Event-ID 同源）。 */
    public long getSeq() {
        return seq;
    }

    public MessageState getState() {
        return state;
    }

    /** 状态的中文描述。 */
    public String getStateText() {
        return stateText;
    }

    /** 已失败次数。 */
    public int getRetryCount() {
        return retryCount;
    }

    /** 下次重试时间（已格式化；"—" 表示无调度）。 */
    public String getNextRetryAt() {
        return nextRetryAt;
    }

    /** 最近一次错误信息。 */
    public String getLastError() {
        return lastError;
    }

    /** payload 的 UTF-8 文本预览（超长截断；二进制只标注字节数）。 */
    public String getPayloadPreview() {
        return payloadPreview;
    }

    /** payload 原始字节数。 */
    public int getPayloadSize() {
        return payloadSize;
    }

    /** 首次创建时间（已格式化）。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 最近更新时间（已格式化）。 */
    public String getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "DeliveryView{messageId='" + messageId + "', clientId='" + clientId
                + "', seq=" + seq + ", state=" + state + ", retryCount=" + retryCount + "}";
    }
}
