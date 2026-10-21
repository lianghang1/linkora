package com.linkora.core.message;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖状态机的关键约束：禁止回退已完成状态、禁止跳过阶段、终态无出边、幂等转移放行。
 *
 * @author lianghang
 */
public class MessageStateTest {

    @Test
    public void normalLifecycleTransitions() {
        assertTrue(MessageState.CREATED.canTransitionTo(MessageState.ACCEPTED));
        assertTrue(MessageState.CREATED.canTransitionTo(MessageState.RECEIVED));
        assertTrue(MessageState.ACCEPTED.canTransitionTo(MessageState.RECEIVED));
        assertTrue(MessageState.RECEIVED.canTransitionTo(MessageState.PROCESSED));
        assertTrue(MessageState.RECEIVED.canTransitionTo(MessageState.FAILED));
        assertTrue(MessageState.FAILED.canTransitionTo(MessageState.RETRYING));
        assertTrue(MessageState.RETRYING.canTransitionTo(MessageState.PROCESSED));
        assertTrue(MessageState.RETRYING.canTransitionTo(MessageState.FAILED));
    }

    @Test
    public void expiryAndDeadLetterReachable() {
        assertTrue(MessageState.CREATED.canTransitionTo(MessageState.EXPIRED));
        assertTrue(MessageState.ACCEPTED.canTransitionTo(MessageState.EXPIRED));
        assertTrue(MessageState.RECEIVED.canTransitionTo(MessageState.DEAD_LETTER));
        assertTrue(MessageState.FAILED.canTransitionTo(MessageState.DEAD_LETTER));
    }

    @Test
    public void completedStatesCannotRegress() {
        // 核心 P0：旧请求不得将已完成状态错误回退
        assertFalse(MessageState.PROCESSED.canTransitionTo(MessageState.RECEIVED));
        assertFalse(MessageState.PROCESSED.canTransitionTo(MessageState.RETRYING));
        assertFalse(MessageState.PROCESSED.canTransitionTo(MessageState.FAILED));
        assertFalse(MessageState.RECEIVED.canTransitionTo(MessageState.ACCEPTED));
        assertFalse(MessageState.ACCEPTED.canTransitionTo(MessageState.CREATED));
        assertFalse(MessageState.RETRYING.canTransitionTo(MessageState.RECEIVED));
        assertFalse(MessageState.FAILED.canTransitionTo(MessageState.RECEIVED));
    }

    @Test
    public void skipTransitionsForbidden() {
        assertFalse(MessageState.CREATED.canTransitionTo(MessageState.PROCESSED));
        assertFalse(MessageState.CREATED.canTransitionTo(MessageState.RETRYING));
        assertFalse(MessageState.ACCEPTED.canTransitionTo(MessageState.PROCESSED));
        assertFalse(MessageState.FAILED.canTransitionTo(MessageState.RECEIVED));
    }

    @Test
    public void terminalStatesHaveNoOutgoingEdges() {
        assertTrue(MessageState.PROCESSED.isTerminal());
        assertTrue(MessageState.EXPIRED.isTerminal());
        for (MessageState target : MessageState.values()) {
            assertFalse(MessageState.PROCESSED.canTransitionTo(target) && target != MessageState.PROCESSED);
            assertFalse(MessageState.EXPIRED.canTransitionTo(target) && target != MessageState.EXPIRED);
        }
    }

    @Test
    public void deadLetterOnlyRevivableByManualRetry() {
        assertTrue(MessageState.DEAD_LETTER.canTransitionTo(MessageState.RETRYING));
        assertFalse(MessageState.DEAD_LETTER.canTransitionTo(MessageState.RECEIVED));
        assertFalse(MessageState.DEAD_LETTER.canTransitionTo(MessageState.CREATED));
        assertFalse(MessageState.DEAD_LETTER.isTerminal());
    }

    @Test
    public void idempotentTransitionAllowed() {
        for (MessageState state : MessageState.values()) {
            assertTrue(state + " 幂等转移应合法", state.canTransitionTo(state));
        }
    }

    @Test
    public void transitionToThrowsOnIllegal() {
        try {
            MessageState.PROCESSED.transitionTo(MessageState.RECEIVED);
            org.junit.Assert.fail("预期抛出 IllegalStateTransitionException");
        } catch (IllegalStateTransitionException expected) {
            assertTrue(expected.getMessage().contains("PROCESSED"));
        }
    }

    @Test
    public void nullTargetRejected() {
        assertFalse(MessageState.CREATED.canTransitionTo(null));
    }
}
