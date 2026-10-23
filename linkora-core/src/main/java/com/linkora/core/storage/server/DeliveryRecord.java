package com.linkora.core.storage.server;

import com.linkora.core.message.MessageState;

/**
 * 服务端投递记录（消息正文与投递状态分离存储，本对象只承载状态维度）。
 *
 * @author lianghang
 */
public final class DeliveryRecord {

    private final String messageId;
    private final String clientId;
    private final long seq;
    private final MessageState state;
    private final int retryCount;
    private final Long nextRetryAt;
    private final String lastError;
    private final long createdAt;
    private final long updatedAt;
    /** 顺序键，可为 null（不参与顺序约束）。 */
    private final String sequenceKey;

    public DeliveryRecord(String messageId, String clientId, long seq, MessageState state,
                          int retryCount, Long nextRetryAt, String lastError,
                          long createdAt, long updatedAt) {
        this(messageId, clientId, seq, state, retryCount, nextRetryAt, lastError,
                createdAt, updatedAt, null);
    }

    public DeliveryRecord(String messageId, String clientId, long seq, MessageState state,
                          int retryCount, Long nextRetryAt, String lastError,
                          long createdAt, long updatedAt, String sequenceKey) {
        if (messageId == null || messageId.trim().isEmpty()) {
            throw new IllegalArgumentException("messageId 不能为空");
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("seq 必须 >= 1");
        }
        if (state == null) {
            throw new IllegalArgumentException("state 不能为 null");
        }
        this.messageId = messageId;
        this.clientId = clientId;
        this.seq = seq;
        this.state = state;
        this.retryCount = retryCount;
        this.nextRetryAt = nextRetryAt;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sequenceKey = sequenceKey;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getClientId() {
        return clientId;
    }

    /** 服务端为每个客户端分配的递增投递序号，用于 Last-Event-ID 与跳号检测。 */
    public long getSeq() {
        return seq;
    }

    public MessageState getState() {
        return state;
    }

    public int getRetryCount() {
        return retryCount;
    }

    /** 下次重试时间（epoch 毫秒），可为 null 表示不调度重试。 */
    public Long getNextRetryAt() {
        return nextRetryAt;
    }

    public String getLastError() {
        return lastError;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /** 顺序键，可为 null。 */
    public String getSequenceKey() {
        return sequenceKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DeliveryRecord)) {
            return false;
        }
        DeliveryRecord that = (DeliveryRecord) o;
        return messageId.equals(that.messageId) && clientId.equals(that.clientId);
    }

    @Override
    public int hashCode() {
        return 31 * messageId.hashCode() + clientId.hashCode();
    }

    @Override
    public String toString() {
        return "DeliveryRecord{messageId='" + messageId + '\''
                + ", clientId='" + clientId + '\''
                + ", seq=" + seq + ", state=" + state
                + ", retryCount=" + retryCount + '}';
    }
}
