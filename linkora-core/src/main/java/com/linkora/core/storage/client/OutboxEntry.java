package com.linkora.core.storage.client;

import com.linkora.core.message.OutboxStatus;

/**
 * 客户端 outbox 条目（线下→线上待发送消息），对应 SQLite 表 lk_outbox。
 * payload 为已序列化字节，入队时即定型；重试沿用原 messageId。
 *
 * @author lianghang
 */
public final class OutboxEntry {

    /** 与 {@code LinkoraMessage.MAX_ORDERING_KEY_LENGTH} 对齐。 */
    public static final int MAX_ORDERING_KEY_LENGTH = 128;

    private final String messageId;
    private final String messageType;
    private final byte[] payload;
    private final OutboxStatus status;
    private final long createdAt;
    private final long updatedAt;
    private final int retryCount;
    private final Long nextRetryAt;
    private final Long expiresAt;
    private final String lastError;
    /** 顺序键，可为 null（不参与顺序约束）。 */
    private final String orderingKey;

    public OutboxEntry(String messageId, String messageType, byte[] payload,
                       OutboxStatus status, long createdAt, long updatedAt,
                       int retryCount, Long nextRetryAt, Long expiresAt, String lastError) {
        this(messageId, messageType, payload, status, createdAt, updatedAt,
                retryCount, nextRetryAt, expiresAt, lastError, null);
    }

    public OutboxEntry(String messageId, String messageType, byte[] payload,
                       OutboxStatus status, long createdAt, long updatedAt,
                       int retryCount, Long nextRetryAt, Long expiresAt, String lastError,
                       String orderingKey) {
        requireNonBlank(messageId, "messageId");
        requireNonBlank(messageType, "messageType");
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为 null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为 null");
        }
        this.messageId = messageId;
        this.messageType = messageType;
        this.payload = java.util.Arrays.copyOf(payload, payload.length);
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.retryCount = retryCount;
        this.nextRetryAt = nextRetryAt;
        this.expiresAt = expiresAt;
        this.lastError = lastError;
        // 与 LinkoraMessage 保持一致：null 与空白都归一为 null（= 不参与顺序约束），
        // 否则空串会变成一条"所有空串共用"的假车道，把无关消息串行起来
        String key = orderingKey == null || orderingKey.trim().isEmpty()
                ? null : orderingKey.trim();
        if (key != null && key.length() > MAX_ORDERING_KEY_LENGTH) {
            // 提前失败：否则要等到发送时才由 LinkoraMessage 抛异常，那时消息已进死信
            throw new IllegalArgumentException("orderingKey 长度不得超过 "
                    + MAX_ORDERING_KEY_LENGTH + "（实际 " + key.length() + "）");
        }
        this.orderingKey = key;
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

    public OutboxStatus getStatus() {
        return status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public Long getNextRetryAt() {
        return nextRetryAt;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public String getLastError() {
        return lastError;
    }

    /** 顺序键，可为 null。 */
    public String getOrderingKey() {
        return orderingKey;
    }

    /** 返回携带更新字段的新实例（不可变对象的变更语义）。 */
    public OutboxEntry with(OutboxStatus newStatus, int newRetryCount,
                            Long newNextRetryAt, String newLastError, long now) {
        return new OutboxEntry(messageId, messageType, payload, newStatus,
                createdAt, now, newRetryCount, newNextRetryAt, expiresAt, newLastError,
                orderingKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OutboxEntry)) {
            return false;
        }
        return messageId.equals(((OutboxEntry) o).messageId);
    }

    @Override
    public int hashCode() {
        return messageId.hashCode();
    }

    @Override
    public String toString() {
        return "OutboxEntry{messageId='" + messageId + "', messageType='" + messageType
                + "', status=" + status + ", retryCount=" + retryCount + '}';
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
