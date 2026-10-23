package com.linkora.client.admin;

import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import com.linkora.core.storage.client.InboxEntry;

/**
 * 下行收件条目的管理视图（人类可读）：两级状态（接收/处理）均为中文描述。
 *
 * @author lianghang
 */
public final class InboxView {

    private final String messageId;
    private final String messageType;
    private final String receiveStatusText;
    private final String processStatusText;
    private final String payloadPreview;
    private final int payloadSize;
    private final int retryCount;
    private final String lastError;
    private final String receivedAt;
    private final String processedAt;

    public InboxView(InboxEntry entry) {
        this.messageId = entry.getMessageId();
        this.messageType = entry.getMessageType();
        this.receiveStatusText = receiveText(entry.getReceiveStatus());
        this.processStatusText = processText(entry.getProcessStatus());
        this.payloadPreview = OutboxView.preview(entry.getPayload());
        this.payloadSize = entry.getPayload().length;
        this.retryCount = entry.getRetryCount();
        this.lastError = entry.getLastError() == null ? "—" : entry.getLastError();
        this.receivedAt = OutboxView.formatTime(entry.getReceivedAt());
        this.processedAt = OutboxView.formatTime(entry.getProcessedAt());
    }

    public String getMessageId() {
        return messageId;
    }

    public String getMessageType() {
        return messageType;
    }

    /** 接收状态中文描述。 */
    public String getReceiveStatusText() {
        return receiveStatusText;
    }

    /** 处理状态中文描述。 */
    public String getProcessStatusText() {
        return processStatusText;
    }

    public String getPayloadPreview() {
        return payloadPreview;
    }

    public int getPayloadSize() {
        return payloadSize;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public String getLastError() {
        return lastError;
    }

    /** 接收时间（已格式化）。 */
    public String getReceivedAt() {
        return receivedAt;
    }

    /** 处理完成时间（已格式化；"—" 表示未完成）。 */
    public String getProcessedAt() {
        return processedAt;
    }

    @Override
    public String toString() {
        return "InboxView{messageId='" + messageId + "', processStatus=" + processStatusText + "}";
    }

    private static String receiveText(ReceiveStatus status) {
        switch (status) {
            case PERSISTED:          return "已落库，待发送接收确认";
            case RECEIVED_ACK_SENT:  return "接收确认已发送";
            default:                 return status.name();
        }
    }

    private static String processText(ProcessStatus status) {
        switch (status) {
            case PENDING:    return "待处理";
            case PROCESSING: return "处理中";
            case PROCESSED:  return "处理成功（终态）";
            case RETRYING:   return "处理失败，重试中";
            case FAILED:     return "处理失败次数耗尽，等待服务端重投";
            default:         return status.name();
        }
    }
}
