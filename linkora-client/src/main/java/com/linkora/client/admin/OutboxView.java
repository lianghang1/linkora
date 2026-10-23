package com.linkora.client.admin;

import com.linkora.core.storage.client.OutboxEntry;

/**
 * 上行队列条目的管理视图（人类可读）：时间为格式化字符串，
 * payload 为 UTF-8 文本预览，绝不输出 Base64。
 *
 * @author lianghang
 */
public final class OutboxView {

    private static final int PREVIEW_MAX_CHARS = 200;

    private final String messageId;
    private final String messageType;
    private final String statusText;
    private final String payloadPreview;
    private final int payloadSize;
    private final int retryCount;
    private final String lastError;
    private final String createdAt;
    private final String updatedAt;

    public OutboxView(OutboxEntry entry) {
        this.messageId = entry.getMessageId();
        this.messageType = entry.getMessageType();
        this.statusText = statusText(entry.getStatus());
        this.payloadPreview = preview(entry.getPayload());
        this.payloadSize = entry.getPayload().length;
        this.retryCount = entry.getRetryCount();
        this.lastError = entry.getLastError() == null ? "—" : entry.getLastError();
        this.createdAt = formatTime(entry.getCreatedAt());
        this.updatedAt = formatTime(entry.getUpdatedAt());
    }

    public String getMessageId() {
        return messageId;
    }

    public String getMessageType() {
        return messageType;
    }

    /** 状态的中文描述（待发送/发送中/服务端已确认/重试中/死信）。 */
    public String getStatusText() {
        return statusText;
    }

    /** payload 的 UTF-8 文本预览（超长截断；二进制只标注字节数）。 */
    public String getPayloadPreview() {
        return payloadPreview;
    }

    /** payload 原始字节数。 */
    public int getPayloadSize() {
        return payloadSize;
    }

    /** 已重试次数。 */
    public int getRetryCount() {
        return retryCount;
    }

    /** 最近一次错误信息。 */
    public String getLastError() {
        return lastError;
    }

    /** 入队时间（已格式化）。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 最近更新时间（已格式化）。 */
    public String getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "OutboxView{messageId='" + messageId + "', status=" + statusText + "}";
    }

    static String statusText(com.linkora.core.message.OutboxStatus status) {
        switch (status) {
            case PENDING:         return "待发送";
            case SENDING:         return "发送中";
            case SERVER_ACCEPTED: return "服务端已确认（终态）";
            case FAILED:          return "发送失败，待重试";
            case DEAD_LETTER:     return "死信，等待人工介入";
            default:              return status.name();
        }
    }

    static String formatTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return "—";
        }
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    static String preview(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "（空）";
        }
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t' && (c < 0x20 || c == 0x7F)) {
                return "[二进制数据 " + payload.length + " 字节]";
            }
        }
        if (text.length() > PREVIEW_MAX_CHARS) {
            return text.substring(0, PREVIEW_MAX_CHARS) + "…（共 " + text.length() + " 字符）";
        }
        return text;
    }
}
