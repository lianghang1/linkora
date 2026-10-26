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
    private final String sequenceKey;
    /** 人类可读主题（可选，供运维界面展示；不参与协议语义）。 */
    public static final int MAX_SUBJECT_LENGTH = 255;
    private final String subject;

    /** 线路标记：payload 是否加密（A256GCM:keyId）；仅传输语义，不参与 equals/持久化。 */
    private final String enc;

    /** 顺序键长度上限（与库表列宽一致）。 */
    public static final int MAX_SEQUENCE_KEY_LENGTH = 128;

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
        if (builder.sequenceKey != null && builder.sequenceKey.length() > MAX_SEQUENCE_KEY_LENGTH) {
            throw new IllegalArgumentException("sequenceKey 长度不得超过 " + MAX_SEQUENCE_KEY_LENGTH);
        }
        this.sequenceKey = builder.sequenceKey;
        if (builder.subject != null && builder.subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("subject 长度不得超过 " + MAX_SUBJECT_LENGTH);
        }
        this.subject = builder.subject;
        this.enc = builder.enc;
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

    /**
     * 顺序键（可选），为 null 表示不参与顺序约束。
     *
     * <p>同一个 clientId 下，{@code sequenceKey} 相同的消息严格按发布顺序（seq 单调）
     * 投递：前一条到达终态之前，后一条不会被推送。订单号只是其中一种用法，
     * 任何"必须按顺序处理"的实体（账户、设备、单据）都可以作为键。</p>
     *
     * <p>键为 null 时保持原有的"尽力投递、允许乱序"语义，与其他消息互不影响。</p>
     */
    public String getSequenceKey() {
        return sequenceKey;
    }

    /** 可为 null 表示未设置（显示时回退到 messageType）。 */
    public String getSubject() {
        return subject;
    }

    /** enc 线路标记（可能为 null）。 */
    public String getEnc() {
        return enc;
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
                && java.util.Objects.equals(traceId, that.traceId)
                && java.util.Objects.equals(sequenceKey, that.sequenceKey)
                && java.util.Objects.equals(subject, that.subject);
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
        result = 31 * result + java.util.Objects.hashCode(sequenceKey);
        result = 31 * result + java.util.Objects.hashCode(subject);
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
                + ", traceId='" + traceId + '\''
                + ", sequenceKey='" + sequenceKey + "'}";
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
        private String sequenceKey;

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

        /**
         * 可选：顺序键，null / 空串 / 空白统一归一化为 null（= 无键，不参与顺序约束）。
         * SDK 不做任何自动拼接，键的粒度完全由业务决定（如订单号）。
         */
        public Builder sequenceKey(String sequenceKey) {
            this.sequenceKey = sequenceKey == null || sequenceKey.trim().isEmpty()
                    ? null : sequenceKey.trim();
            return this;
        }

        private String subject;

        private String enc;

        /**
         * 可选：一句话人类可读主题（如「优惠券补发 RV-2001」），供运维控制台等
         * 界面直接展示。null / 空白归一化为 null；不参与协议语义与投递逻辑。
         */
        public Builder subject(String subject) {
            this.subject = subject == null || subject.trim().isEmpty()
                    ? null : subject.trim();
            return this;
        }

        /** enc 线路标记（A256GCM:keyId）；由加密切换点设置，业务方无需关心。 */
        public Builder enc(String enc) {
            this.enc = enc;
            return this;
        }

        public LinkoraMessage build() {
            return new LinkoraMessage(this);
        }
    }
}
