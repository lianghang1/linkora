package com.linkora.core.message;

import java.util.Arrays;

/**
 * Linkora 统一消息模型（不可变对象）。
 *
 * <p>上行（线下→线上）与下行（线上→线下）共用同一消息结构。
 * {@code clientId} 在上行表示来源客户端，在下行表示目标客户端；
 * 服务端必须依据认证上下文校验 clientId，不能只信任请求体中的值。</p>
 *
 * <p>时间字段统一使用 epoch 毫秒，避免时区与时钟类型歧义。</p>
 *
 * @author lianghang
 */
public final class LinkoraMessage {

    private final String messageId;
    private final String clientId;
    private final String messageType;
    private final byte[] payload;
    private final long createdAt;
    private final Long expiresAt;
    private final int schemaVersion;
    private final String traceId;

    private LinkoraMessage(Builder builder) {
        this.messageId = requireNonBlank(builder.messageId, "messageId");
        this.clientId = requireNonBlank(builder.clientId, "clientId");
        this.messageType = requireNonBlank(builder.messageType, "messageType");
        if (builder.payload == null) {
            throw new IllegalArgumentException("payload 不能为 null");
        }
        this.payload = Arrays.copyOf(builder.payload, builder.payload.length);
        if (builder.createdAt <= 0) {
            throw new IllegalArgumentException("createdAt 必须为正的 epoch 毫秒");
        }
        this.createdAt = builder.createdAt;
        if (builder.expiresAt != null && builder.expiresAt <= builder.createdAt) {
            throw new IllegalArgumentException("expiresAt 必须晚于 createdAt");
        }
        this.expiresAt = builder.expiresAt;
        if (builder.schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 必须 >= 1");
        }
        this.schemaVersion = builder.schemaVersion;
        this.traceId = builder.traceId;
    }

    public static Builder builder() {
        return new Builder();
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

    /** 返回内部数组的防御性拷贝。 */
    public byte[] getPayload() {
        return Arrays.copyOf(payload, payload.length);
    }

    public int getPayloadSize() {
        return payload.length;
    }

    /** 以 UTF-8 解码 payload 的便捷方法（默认 JSON 序列化场景使用）。 */
    public String getPayloadAsUtf8String() {
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /** 可为 null 表示不过期。 */
    public Long getExpiresAt() {
        return expiresAt;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    /** 可为 null。 */
    public String getTraceId() {
        return traceId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LinkoraMessage)) {
            return false;
        }
        LinkoraMessage that = (LinkoraMessage) o;
        return createdAt == that.createdAt
                && schemaVersion == that.schemaVersion
                && messageId.equals(that.messageId)
                && clientId.equals(that.clientId)
                && messageType.equals(that.messageType)
                && Arrays.equals(payload, that.payload)
                && java.util.Objects.equals(expiresAt, that.expiresAt)
                && java.util.Objects.equals(traceId, that.traceId);
    }

    @Override
    public int hashCode() {
        int result = messageId.hashCode();
        result = 31 * result + clientId.hashCode();
        result = 31 * result + messageType.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + Long.hashCode(createdAt);
        result = 31 * result + java.util.Objects.hashCode(expiresAt);
        result = 31 * result + schemaVersion;
        result = 31 * result + java.util.Objects.hashCode(traceId);
        return result;
    }

    /** 注意：不输出 payload 内容，防止敏感业务数据进入日志。 */
    @Override
    public String toString() {
        return "LinkoraMessage{messageId='" + messageId + '\''
                + ", clientId='" + clientId + '\''
                + ", messageType='" + messageType + '\''
                + ", payloadSize=" + payload.length
                + ", createdAt=" + createdAt
                + ", expiresAt=" + expiresAt
                + ", schemaVersion=" + schemaVersion
                + ", traceId='" + traceId + "'}";
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    public static final class Builder {
        private String messageId;
        private String clientId;
        private String messageType;
        private byte[] payload;
        private long createdAt;
        private Long expiresAt;
        private int schemaVersion = 1;
        private String traceId;

        public Builder messageId(String messageId) {
            this.messageId = messageId;
            return this;
        }

        public Builder clientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public Builder messageType(String messageType) {
            this.messageType = messageType;
            return this;
        }

        public Builder payload(byte[] payload) {
            this.payload = payload;
            return this;
        }

        public Builder payload(String utf8Payload) {
            this.payload = utf8Payload == null
                    ? null
                    : utf8Payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return this;
        }

        public Builder createdAt(long epochMillis) {
            this.createdAt = epochMillis;
            return this;
        }

        public Builder expiresAt(Long epochMillis) {
            this.expiresAt = epochMillis;
            return this;
        }

        public Builder schemaVersion(int schemaVersion) {
            this.schemaVersion = schemaVersion;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public LinkoraMessage build() {
            return new LinkoraMessage(this);
        }
    }
}
