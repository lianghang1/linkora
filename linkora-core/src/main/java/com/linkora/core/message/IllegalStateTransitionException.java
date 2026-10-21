package com.linkora.core.message;

/**
 * 非法状态转移异常。表明调用方基于过期状态做出了决策，
 * 应重新读取最新状态后重放处理逻辑，而不是盲目重试同一转移。
 *
 * @author lianghang
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final MessageState current;
    private final MessageState target;

    public IllegalStateTransitionException(MessageState current, MessageState target) {
        super("非法状态转移: " + current + " -> " + target);
        this.current = current;
        this.target = target;
    }

    public MessageState getCurrent() {
        return current;
    }

    public MessageState getTarget() {
        return target;
    }
}
