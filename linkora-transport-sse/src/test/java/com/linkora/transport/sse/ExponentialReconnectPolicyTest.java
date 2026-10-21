package com.linkora.transport.sse;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExponentialReconnectPolicyTest {

    @Test
    public void backoffDoublesAndCaps() {
        ExponentialReconnectPolicy policy = new ExponentialReconnectPolicy(
                100L, 1000L, 2.0, 0.0);
        assertEquals(100L, policy.nextDelayMs(1, null));
        assertEquals(200L, policy.nextDelayMs(2, null));
        assertEquals(400L, policy.nextDelayMs(3, null));
        assertEquals(800L, policy.nextDelayMs(4, null));
        assertEquals(1000L, policy.nextDelayMs(5, null));
        assertEquals(1000L, policy.nextDelayMs(50, null));
    }

    @Test
    public void jitterStaysWithinBounds() {
        ExponentialReconnectPolicy policy = new ExponentialReconnectPolicy(
                1000L, 60_000L, 2.0, 0.2);
        for (int attempt = 1; attempt <= 10; attempt++) {
            long delay = policy.nextDelayMs(attempt, null);
            long expected = Math.min(1000L * (1L << (attempt - 1)), 60_000L);
            long low = (long) Math.max(0, expected - expected * 0.2);
            long high = (long) (expected + expected * 0.2);
            assertTrue("延迟应在抖动区间内: " + delay + " not in [" + low + "," + high + "]",
                    delay >= low && delay <= high);
        }
    }

    @Test
    public void serverSuggestionWinsButCapped() {
        ExponentialReconnectPolicy policy = new ExponentialReconnectPolicy(
                100L, 1000L, 2.0, 0.2);
        assertEquals(500L, policy.nextDelayMs(3, 500L));
        assertEquals(1000L, policy.nextDelayMs(3, 999_999L));
    }

    @Test
    public void rejectsInvalidArgs() {
        ExponentialReconnectPolicy policy = ExponentialReconnectPolicy.defaultPolicy();
        try {
            policy.nextDelayMs(0, null);
            throw new AssertionError("attempt < 1 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new ExponentialReconnectPolicy(0L, 1000L, 2.0, 0.1);
            throw new AssertionError("baseMs <= 0 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new ExponentialReconnectPolicy(100L, 50L, 2.0, 0.1);
            throw new AssertionError("maxMs < baseMs 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
