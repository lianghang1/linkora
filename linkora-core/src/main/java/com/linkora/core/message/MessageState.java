package com.linkora.core.message;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 消息生命周期状态。
 *
 * <p>状态转移只能沿生命周期前进；旧请求不得将已完成状态错误回退
 * （例如重复的 RECEIVED ACK 不能把 PROCESSED 拉回 RECEIVED）。</p>
 *
 * <p>本枚举的转移校验是纯函数；并发安全性由存储层保证：
 * 实现方必须以 CAS 方式（期望旧状态 + 目标状态）提交状态更新，
 * 竞争失败的调用方以最新状态重放决策。</p>
 */
public enum MessageState {

    /** 已创建。 */
    CREATED,

    /** 服务端已持久化接受（上行终态；下行作为投递起点）。 */
    ACCEPTED,

    /** 客户端已持久化接收。 */
    RECEIVED,

    /** 业务处理成功（终态）。 */
    PROCESSED,

    /** 业务处理失败（可恢复）。 */
    FAILED,

    /** 待重试。 */
    RETRYING,

    /** 已过期（终态）。 */
    EXPIRED,

    /** 死信/人工介入（仅允许人工重试复活为 RETRYING）。 */
    DEAD_LETTER;

    private static final Map<MessageState, Set<MessageState>> ALLOWED;

    static {
        Map<MessageState, Set<MessageState>> map = new EnumMap<>(MessageState.class);
        map.put(CREATED, EnumSet.of(ACCEPTED, RECEIVED, EXPIRED, DEAD_LETTER));
        map.put(ACCEPTED, EnumSet.of(RECEIVED, EXPIRED, DEAD_LETTER));
        map.put(RECEIVED, EnumSet.of(PROCESSED, FAILED, EXPIRED, DEAD_LETTER));
        map.put(FAILED, EnumSet.of(RETRYING, EXPIRED, DEAD_LETTER));
        map.put(RETRYING, EnumSet.of(PROCESSED, FAILED, EXPIRED, DEAD_LETTER));
        map.put(PROCESSED, Collections.<MessageState>emptySet());
        map.put(EXPIRED, Collections.<MessageState>emptySet());
        map.put(DEAD_LETTER, EnumSet.of(RETRYING));
        ALLOWED = Collections.unmodifiableMap(map);
    }

    /**
     * 校验从当前状态到目标状态的转移是否合法。
     * 幂等转移（目标 == 当前）视为合法，由调用方决定是否跳过写入。
     */
    public boolean canTransitionTo(MessageState target) {
        if (target == null) {
            return false;
        }
        if (target == this) {
            return true;
        }
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 校验并返回目标状态；非法转移抛出
     * {@link IllegalStateTransitionException}。
     */
    public MessageState transitionTo(MessageState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateTransitionException(this, target);
        }
        return target;
    }

    /** 终态：没有任何出边（DEAD_LETTER 因人工重试不算终态）。 */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
