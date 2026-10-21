package com.linkora.server;

/** 服务端可调参数（全部有界校验，缺省值适合多数部署）。 */
public final class ServerConfig {

    private final int pushBatchSize;
    private final long sweepIntervalMs;
    private final int maxDeliveryRetries;
    private final long retryBackoffBaseMs;
    private final long retryBackoffMaxMs;
    private final long pushPacingBaseMs;
    private final long pushPacingMaxMs;
    private final long messageSizeLimitBytes;

    private ServerConfig(Builder b) {
        this.pushBatchSize = b.pushBatchSize;
        this.sweepIntervalMs = b.sweepIntervalMs;
        this.maxDeliveryRetries = b.maxDeliveryRetries;
        this.retryBackoffBaseMs = b.retryBackoffBaseMs;
        this.retryBackoffMaxMs = b.retryBackoffMaxMs;
        this.pushPacingBaseMs = b.pushPacingBaseMs;
        this.pushPacingMaxMs = b.pushPacingMaxMs;
        this.messageSizeLimitBytes = b.messageSizeLimitBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 单次补发扫描中，每个客户端最多处理的投递记录数。 */
    public int getPushBatchSize() {
        return pushBatchSize;
    }

    /** 补发/重推扫描周期（毫秒）。 */
    public long getSweepIntervalMs() {
        return sweepIntervalMs;
    }

    /** 客户端 FAILED ACK 累计多少次后进入死信。 */
    public int getMaxDeliveryRetries() {
        return maxDeliveryRetries;
    }

    /** 失败重试退避基数（毫秒），指数增长。 */
    public long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    /** 失败重试退避上限（毫秒）。 */
    public long getRetryBackoffMaxMs() {
        return retryBackoffMaxMs;
    }

    /** 未投递成功消息的重推间隔基数（毫秒），指数增长避免风暴。 */
    public long getPushPacingBaseMs() {
        return pushPacingBaseMs;
    }

    /** 未投递成功消息的重推间隔上限（毫秒）。 */
    public long getPushPacingMaxMs() {
        return pushPacingMaxMs;
    }

    /** 上行消息体大小上限（字节）。 */
    public long getMessageSizeLimitBytes() {
        return messageSizeLimitBytes;
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
        private int pushBatchSize = 100;
        private long sweepIntervalMs = 5_000L;
        private int maxDeliveryRetries = 10;
        private long retryBackoffBaseMs = 2_000L;
        private long retryBackoffMaxMs = 300_000L;
        private long pushPacingBaseMs = 5_000L;
        private long pushPacingMaxMs = 60_000L;
        private long messageSizeLimitBytes = 1024 * 1024L;

        public Builder pushBatchSize(int pushBatchSize) {
            if (pushBatchSize < 1 || pushBatchSize > 10_000) {
                throw new IllegalArgumentException("pushBatchSize 取值范围 [1, 10000]");
            }
            this.pushBatchSize = pushBatchSize;
            return this;
        }

        public Builder sweepIntervalMs(long sweepIntervalMs) {
            if (sweepIntervalMs < 100) {
                throw new IllegalArgumentException("sweepIntervalMs 不得小于 100ms");
            }
            this.sweepIntervalMs = sweepIntervalMs;
            return this;
        }

        public Builder maxDeliveryRetries(int maxDeliveryRetries) {
            if (maxDeliveryRetries < 1 || maxDeliveryRetries > 1000) {
                throw new IllegalArgumentException("maxDeliveryRetries 取值范围 [1, 1000]");
            }
            this.maxDeliveryRetries = maxDeliveryRetries;
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

        public Builder pushPacingBaseMs(long pushPacingBaseMs) {
            if (pushPacingBaseMs < 1) {
                throw new IllegalArgumentException("pushPacingBaseMs 必须为正数");
            }
            this.pushPacingBaseMs = pushPacingBaseMs;
            return this;
        }

        public Builder pushPacingMaxMs(long pushPacingMaxMs) {
            if (pushPacingMaxMs < 1) {
                throw new IllegalArgumentException("pushPacingMaxMs 必须为正数");
            }
            this.pushPacingMaxMs = pushPacingMaxMs;
            return this;
        }

        public Builder messageSizeLimitBytes(long messageSizeLimitBytes) {
            if (messageSizeLimitBytes < 1 || messageSizeLimitBytes > 16 * 1024 * 1024L) {
                throw new IllegalArgumentException("messageSizeLimitBytes 取值范围 [1, 16MB]");
            }
            this.messageSizeLimitBytes = messageSizeLimitBytes;
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
