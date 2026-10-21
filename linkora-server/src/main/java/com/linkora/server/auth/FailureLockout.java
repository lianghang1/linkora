package com.linkora.server.auth;

/**
 * 认证失败指数锁定：同一键（clientId）连续失败 N 次后，按指数退避锁定。
 * 锁定期间直接拒绝（不暴露具体原因给攻击面），成功认证即清零。
 *
 * <p>纯内存实现：重启即清零是可接受的降级（攻击者重启服务端无法获益，
 * 锁定的主要目的是拖慢爆破速度而非精确审计）。线程安全。</p>
 *
 * @author lianghang
 */
public final class FailureLockout {

    private final int maxFailures;
    private final long baseLockoutMs;
    private final long maxLockoutMs;
    private final java.util.concurrent.ConcurrentHashMap<String, State> states =
            new java.util.concurrent.ConcurrentHashMap<String, State>();

    public FailureLockout(int maxFailures, long baseLockoutMs, long maxLockoutMs) {
        if (maxFailures < 1 || baseLockoutMs < 1 || maxLockoutMs < baseLockoutMs) {
            throw new IllegalArgumentException("锁定参数非法");
        }
        this.maxFailures = maxFailures;
        this.baseLockoutMs = baseLockoutMs;
        this.maxLockoutMs = maxLockoutMs;
    }

    /** 当前是否处于锁定期。 */
    public boolean isLocked(String key, long now) {
        State state = states.get(key);
        return state != null && state.lockedUntil > now;
    }

    /** 记录一次失败；返回本次锁定的到期时间（未触发锁定返回 null）。 */
    public Long recordFailure(String key, long now) {
        State current = states.get(key);
        if (current == null) {
            State created = new State();
            current = states.putIfAbsent(key, created);
            if (current == null) {
                current = created;
            }
        }
        synchronized (current) {
            int failures = current.failures + 1;
            current.failures = failures;
            if (failures >= maxFailures) {
                long lockMs = Math.min(baseLockoutMs * (1L << Math.min(failures - maxFailures, 20)),
                        maxLockoutMs);
                current.lockedUntil = now + lockMs;
                return current.lockedUntil;
            }
            return null;
        }
    }

    /** 认证成功：清零失败计数。 */
    public void recordSuccess(String key) {
        State state = states.get(key);
        if (state != null) {
            synchronized (state) {
                state.failures = 0;
                state.lockedUntil = 0;
            }
        }
    }

    private static final class State {
        int failures;
        long lockedUntil;
    }
}
