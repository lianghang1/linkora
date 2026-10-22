package com.linkora.server;

/** 服务端可调参数（全部有界校验，缺省值适合多数部署）。
 *
 * @author lianghang
 */
public final class ServerConfig {

    private final int pushBatchSize;
    private final long sweepIntervalMs;
    private final int maxDeliveryRetries;
    private final long retryBackoffBaseMs;
    private final long retryBackoffMaxMs;
    private final long pushPacingBaseMs;
    private final long pushPacingMaxMs;
    private final long messageSizeLimitBytes;
    private final boolean retentionEnabled;
    private final long retentionMs;
    private final int retentionBatchSize;
    private final int expirySweepBatchSize;

    private ServerConfig(Builder b) {
        this.pushBatchSize = b.pushBatchSize;
        this.sweepIntervalMs = b.sweepIntervalMs;
        this.maxDeliveryRetries = b.maxDeliveryRetries;
        this.retryBackoffBaseMs = b.retryBackoffBaseMs;
        this.retryBackoffMaxMs = b.retryBackoffMaxMs;
        this.pushPacingBaseMs = b.pushPacingBaseMs;
        this.pushPacingMaxMs = b.pushPacingMaxMs;
        this.messageSizeLimitBytes = b.messageSizeLimitBytes;
        this.retentionEnabled = b.retentionEnabled;
        this.retentionMs = b.retentionMs;
        this.retentionBatchSize = b.retentionBatchSize;
        this.expirySweepBatchSize = b.expirySweepBatchSize;
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

    /** 是否启用保留策略（清理终态记录与消息正文）。默认启用。 */
    public boolean isRetentionEnabled() {
        return retentionEnabled;
    }

    /** 终态记录的保留时长（毫秒），超过后由周期扫描分批清理。默认 7 天。 */
    public long getRetentionMs() {
        return retentionMs;
    }

    /** 单次清理的投递记录条数上限。 */
    public int getRetentionBatchSize() {
        return retentionBatchSize;
    }

    /** 单次过期推进的投递记录条数上限（不依赖客户端是否在线）。 */
    public int getExpirySweepBatchSize() {
        return expirySweepBatchSize;
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
        private boolean retentionEnabled = true;
        private long retentionMs = 7L * 24 * 3600 * 1000;
        private int retentionBatchSize = 500;
        private int expirySweepBatchSize = 500;

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

        public Builder retentionEnabled(boolean retentionEnabled) {
            this.retentionEnabled = retentionEnabled;
            return this;
        }

        public Builder retentionMs(long retentionMs) {
            if (retentionMs < 60_000L) {
                throw new IllegalArgumentException("retentionMs 不得小于 60000ms（避免清理掉仍在重试的消息）");
            }
            this.retentionMs = retentionMs;
            return this;
        }

        public Builder retentionBatchSize(int retentionBatchSize) {
            if (retentionBatchSize < 1 || retentionBatchSize > 10_000) {
                throw new IllegalArgumentException("retentionBatchSize 取值范围 [1, 10000]");
            }
            this.retentionBatchSize = retentionBatchSize;
            return this;
        }

        public Builder expirySweepBatchSize(int expirySweepBatchSize) {
            if (expirySweepBatchSize < 1 || expirySweepBatchSize > 10_000) {
                throw new IllegalArgumentException("expirySweepBatchSize 取值范围 [1, 10000]");
            }
            this.expirySweepBatchSize = expirySweepBatchSize;
            return this;
        }

        public ServerConfig build() {
            return new ServerConfig(this);
        }
    }
}
