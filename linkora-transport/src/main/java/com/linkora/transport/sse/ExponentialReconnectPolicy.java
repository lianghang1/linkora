package com.linkora.transport.sse;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 指数退避 + 抖动的默认重连策略。
 * 延迟 = min(base * multiplier^(attempt-1), max) ± jitter；
 * 服务端显式建议值优先于计算值（同样受 max 封顶）。
 *
 * @author lianghang
 */
public final class ExponentialReconnectPolicy implements ReconnectPolicy {

    private final long baseMs;
    private final long maxMs;
    private final double multiplier;
    /** 抖动比例（0~1），0 表示无抖动。 */
    private final double jitterRatio;

    public ExponentialReconnectPolicy(long baseMs, long maxMs, double multiplier, double jitterRatio) {
        if (baseMs <= 0) {
            throw new IllegalArgumentException("baseMs 必须为正数");
        }
        if (maxMs < baseMs) {
            throw new IllegalArgumentException("maxMs 不得小于 baseMs");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier 不得小于 1.0");
        }
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException("jitterRatio 必须在 [0,1] 区间");
        }
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.multiplier = multiplier;
        this.jitterRatio = jitterRatio;
    }

    public static ExponentialReconnectPolicy defaultPolicy() {
        return new ExponentialReconnectPolicy(1000L, 60_000L, 2.0, 0.2);
    }

    @Override
    public long nextDelayMs(int attempt, Long serverSuggestedMs) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt 从 1 开始");
        }
        if (serverSuggestedMs != null && serverSuggestedMs > 0) {
            return Math.min(serverSuggestedMs, maxMs);
        }
        double raw = baseMs * Math.pow(multiplier, attempt - 1);
        long capped = Math.min((long) raw, maxMs);
        if (jitterRatio <= 0.0) {
            return capped;
        }
        double delta = capped * jitterRatio;
        long low = (long) Math.max(0, capped - delta);
        long high = (long) (capped + delta);
        if (high <= low) {
            return capped;
        }
        return ThreadLocalRandom.current().nextLong(low, high + 1);
    }
}
