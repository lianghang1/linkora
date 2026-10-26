package com.linkora.client;

/**
 * 客户端可调参数（全部有界校验，缺省值适合多数部署）。
 *
 * @author lianghang
 */
public final class ClientConfig {

    private final String eventsPath;
    private final String ingestPath;
    private final String ackPath;
    private final long connectTimeoutMs;
    private final long requestTimeoutMs;
    private final long idleTimeoutMs;
    private final long reconnectMinIntervalMs;
    private final long senderIntervalMs;
    private final long recoveryIntervalMs;
    private final int outboxBatchSize;
    private final int inboxBatchSize;
    private final int outboxMaxRetries;
    private final int processMaxRetries;
    private final long retryBackoffBaseMs;
    private final long retryBackoffMaxMs;
    private final long outboxRetentionMs;
    private final long inboxRetentionMs;
    /** payload 传输加密密钥（Base64, 32 字节）；null = 不加密（内核默认，宿主装配默认开）。 */
    private final String payloadCipherKey;

    private ClientConfig(Builder b) {
        this.eventsPath = b.eventsPath;
        this.ingestPath = b.ingestPath;
        this.ackPath = b.ackPath;
        this.connectTimeoutMs = b.connectTimeoutMs;
        this.requestTimeoutMs = b.requestTimeoutMs;
        this.idleTimeoutMs = b.idleTimeoutMs;
        this.reconnectMinIntervalMs = b.reconnectMinIntervalMs;
        this.senderIntervalMs = b.senderIntervalMs;
        this.recoveryIntervalMs = b.recoveryIntervalMs;
        this.outboxBatchSize = b.outboxBatchSize;
        this.inboxBatchSize = b.inboxBatchSize;
        this.outboxMaxRetries = b.outboxMaxRetries;
        this.processMaxRetries = b.processMaxRetries;
        this.retryBackoffBaseMs = b.retryBackoffBaseMs;
        this.retryBackoffMaxMs = b.retryBackoffMaxMs;
        this.outboxRetentionMs = b.outboxRetentionMs;
        this.inboxRetentionMs = b.inboxRetentionMs;
        this.payloadCipherKey = b.payloadCipherKey;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEventsPath() {
        return eventsPath;
    }

    public String getIngestPath() {
        return ingestPath;
    }

    public String getAckPath() {
        return ackPath;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    /** POST 请求读超时（毫秒）。 */
    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    /** SSE 空闲死链检测超时（毫秒），0 表示禁用。 */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public long getReconnectMinIntervalMs() {
        return reconnectMinIntervalMs;
    }

    /** outbox 补传触发周期（毫秒）。 */
    public long getSenderIntervalMs() {
        return senderIntervalMs;
    }

    /** 恢复扫描周期（毫秒）：ACK 补发、卡死状态复位、清理。 */
    public long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public int getInboxBatchSize() {
        return inboxBatchSize;
    }

    /** outbox 发送失败多少次后进入死信。 */
    public int getOutboxMaxRetries() {
        return outboxMaxRetries;
    }

    /** 业务处理失败多少次后本地进入 FAILED（等待服务端重投驱动续试）。 */
    public int getProcessMaxRetries() {
        return processMaxRetries;
    }

    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    public long getRetryBackoffMaxMs() {
        return retryBackoffMaxMs;
    }

    /** SERVER_ACCEPTED outbox 记录保留时长（毫秒）。 */
    public long getOutboxRetentionMs() {
        return outboxRetentionMs;
    }

    /** 已完成 inbox 记录保留时长（毫秒）。 */
    /** payload 传输加密密钥（可能为 null = 不加密）。 */
    public String getPayloadCipherKey() {
        return payloadCipherKey;
    }

    public long getInboxRetentionMs() {
        return inboxRetentionMs;
    }

    /** 指数退避：base * 2^(attempt-1)，封顶 max。attempt 从 1 开始。 */
    public long backoffDelayMs(int attempt) {
        if (attempt <= 1) {
            return retryBackoffBaseMs;
        }
        long delay = retryBackoffBaseMs;
        for (int i = 1; i < attempt && delay < retryBackoffMaxMs; i++) {
            delay = Math.min(delay * 2, retryBackoffMaxMs);
        }
        return Math.min(delay, retryBackoffMaxMs);
    }

    public static final class Builder {
        private String eventsPath = "/events";
        private String ingestPath = "/messages";
        private String ackPath = "/acks";
        private long connectTimeoutMs = 10_000L;
        private long requestTimeoutMs = 15_000L;
        private long idleTimeoutMs = 45_000L;
        private long reconnectMinIntervalMs = 0L;
        private long senderIntervalMs = 1_000L;
        private long recoveryIntervalMs = 30_000L;
        private int outboxBatchSize = 50;
        private int inboxBatchSize = 50;
        private int outboxMaxRetries = 12;
        private int processMaxRetries = 5;
        private long retryBackoffBaseMs = 2_000L;
        private long retryBackoffMaxMs = 300_000L;
        private long outboxRetentionMs = 7L * 24 * 3600 * 1000;
        private long inboxRetentionMs = 7L * 24 * 3600 * 1000;

        public Builder eventsPath(String eventsPath) {
            this.eventsPath = requirePath(eventsPath);
            return this;
        }

        public Builder ingestPath(String ingestPath) {
            this.ingestPath = requirePath(ingestPath);
            return this;
        }

        public Builder ackPath(String ackPath) {
            this.ackPath = requirePath(ackPath);
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            if (connectTimeoutMs < 1) {
                throw new IllegalArgumentException("connectTimeoutMs 必须为正数");
            }
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder requestTimeoutMs(long requestTimeoutMs) {
            if (requestTimeoutMs < 1) {
                throw new IllegalArgumentException("requestTimeoutMs 必须为正数");
            }
            this.requestTimeoutMs = requestTimeoutMs;
            return this;
        }

        public Builder idleTimeoutMs(long idleTimeoutMs) {
            if (idleTimeoutMs < 0) {
                throw new IllegalArgumentException("idleTimeoutMs 不得为负数");
            }
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        public Builder senderIntervalMs(long senderIntervalMs) {
            if (senderIntervalMs < 50) {
                throw new IllegalArgumentException("senderIntervalMs 不得小于 50ms");
            }
            this.senderIntervalMs = senderIntervalMs;
            return this;
        }

        public Builder recoveryIntervalMs(long recoveryIntervalMs) {
            if (recoveryIntervalMs < 100) {
                throw new IllegalArgumentException("recoveryIntervalMs 不得小于 100ms");
            }
            this.recoveryIntervalMs = recoveryIntervalMs;
            return this;
        }

        public Builder outboxBatchSize(int outboxBatchSize) {
            if (outboxBatchSize < 1 || outboxBatchSize > 1000) {
                throw new IllegalArgumentException("outboxBatchSize 取值范围 [1, 1000]");
            }
            this.outboxBatchSize = outboxBatchSize;
            return this;
        }

        public Builder inboxBatchSize(int inboxBatchSize) {
            if (inboxBatchSize < 1 || inboxBatchSize > 1000) {
                throw new IllegalArgumentException("inboxBatchSize 取值范围 [1, 1000]");
            }
            this.inboxBatchSize = inboxBatchSize;
            return this;
        }

        public Builder outboxMaxRetries(int outboxMaxRetries) {
            if (outboxMaxRetries < 1 || outboxMaxRetries > 1000) {
                throw new IllegalArgumentException("outboxMaxRetries 取值范围 [1, 1000]");
            }
            this.outboxMaxRetries = outboxMaxRetries;
            return this;
        }

        public Builder processMaxRetries(int processMaxRetries) {
            if (processMaxRetries < 1 || processMaxRetries > 1000) {
                throw new IllegalArgumentException("processMaxRetries 取值范围 [1, 1000]");
            }
            this.processMaxRetries = processMaxRetries;
            return this;
        }

        public Builder retryBackoffBaseMs(long retryBackoffBaseMs) {
            if (retryBackoffBaseMs < 1) {
                throw new IllegalArgumentException("retryBackoffBaseMs 必须为正数");
            }
            this.retryBackoffBaseMs = retryBackoffBaseMs;
            return this;
        }

        public Builder retryBackoffMaxMs(long retryBackoffMaxMs) {
            if (retryBackoffMaxMs < 1) {
                throw new IllegalArgumentException("retryBackoffMaxMs 必须为正数");
            }
            this.retryBackoffMaxMs = retryBackoffMaxMs;
            return this;
        }

        public Builder outboxRetentionMs(long outboxRetentionMs) {
            if (outboxRetentionMs < 1000) {
                throw new IllegalArgumentException("outboxRetentionMs 不得小于 1 秒");
            }
            this.outboxRetentionMs = outboxRetentionMs;
            return this;
        }

        public Builder inboxRetentionMs(long inboxRetentionMs) {
            if (inboxRetentionMs < 1000) {
                throw new IllegalArgumentException("inboxRetentionMs 不得小于 1 秒");
            }
            this.inboxRetentionMs = inboxRetentionMs;
            return this;
        }

        private String payloadCipherKey;

        /** payload 传输加密密钥（Base64, 32 字节，openssl rand -base64 32）；不配置 = 不加密。 */
        public Builder payloadCipherKey(String base64Key) {
            if (base64Key != null) {
                com.linkora.core.crypto.PayloadCipher.decodeKey(base64Key); // fail-fast 校验
            }
            this.payloadCipherKey = base64Key;
            return this;
        }

        private static String requirePath(String path) {
            if (path == null || !path.startsWith("/") || path.length() < 2) {
                throw new IllegalArgumentException("路径必须以 / 开头且非根: " + path);
            }
            return path;
        }

        public ClientConfig build() {
            return new ClientConfig(this);
        }
    }
}
