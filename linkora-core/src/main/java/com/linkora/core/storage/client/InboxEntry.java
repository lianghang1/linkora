package com.linkora.core.storage.client;

import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;

/**
 * 客户端 inbox 条目（线上→线下已接收消息），对应 SQLite 表 lk_inbox。
 * 接收状态与处理状态独立追踪：RECEIVED ACK ≠ 业务处理成功。
 *
 * @author lianghang
 */
public final class InboxEntry {

    private final String messageId;
    private final String messageType;
    private final byte[] payload;
    private final ReceiveStatus receiveStatus;
    private final ProcessStatus processStatus;
    private final long receivedAt;
    private final Long processedAt;
    private final int retryCount;
    private final Long nextRetryAt;
    private final String lastError;

    public InboxEntry(String messageId, String messageType, byte[] payload,
                      ReceiveStatus receiveStatus, ProcessStatus processStatus,
                      long receivedAt, Long processedAt, int retryCount,
                      Long nextRetryAt, String lastError) {
        requireNonBlank(messageId, "messageId");
        requireNonBlank(messageType, "messageType");
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为 null");
        }
        if (receiveStatus == null) {
            throw new IllegalArgumentException("receiveStatus 不能为 null");
        }
        if (processStatus == null) {
            throw new IllegalArgumentException("processStatus 不能为 null");
        }
        this.messageId = messageId;
        this.messageType = messageType;
        this.payload = java.util.Arrays.copyOf(payload, payload.length);
        this.receiveStatus = receiveStatus;
        this.processStatus = processStatus;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
        this.retryCount = retryCount;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getMessageType() {
        return messageType;
    }

    public byte[] getPayload() {
        return java.util.Arrays.copyOf(payload, payload.length);
    }

    public ReceiveStatus getReceiveStatus() {
        return receiveStatus;
    }

    public ProcessStatus getProcessStatus() {
        return processStatus;
    }

    public long getReceivedAt() {
        return receivedAt;
    }

    public Long getProcessedAt() {
        return processedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Long getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    /** 返回接收状态与处理状态均更新后的新实例（不可变对象的变更语义）。 */
    public InboxEntry with(ReceiveStatus newReceiveStatus, ProcessStatus newProcessStatus,
                           int newRetryCount, Long newNextRetryAt,
                           String newLastError, Long newProcessedAt) {
        return new InboxEntry(messageId, messageType, payload, newReceiveStatus,
                newProcessStatus, receivedAt, newProcessedAt != null ? newProcessedAt : processedAt,
                newRetryCount, newNextRetryAt, newLastError);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InboxEntry)) {
            return false;
        }
        return messageId.equals(((InboxEntry) o).messageId);
    }

    @Override
    public int hashCode() {
        return messageId.hashCode();
    }

    @Override
    public String toString() {
        return "InboxEntry{messageId='" + messageId + "', messageType='" + messageType
                + "', receiveStatus=" + receiveStatus + ", processStatus=" + processStatus
                + ", retryCount=" + retryCount + '}';
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
