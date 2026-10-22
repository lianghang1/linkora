package com.linkora.core.storage.client;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.message.ProcessStatus;
import com.linkora.core.message.ReceiveStatus;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 覆盖 outbox/inbox 条目的入参校验，以及 with 方法对不可变对象的变更语义。
 *
 * @author lianghang
 */
public class ClientEntryTest {

    private byte[] payload() {
        return "data".getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void sequenceKeyValidationAndNormalization() {
        String tooLong = new String(new char[129]).replace('\0', 'x');
        try {
            new OutboxEntry("m1", "T", payload(), OutboxStatus.PENDING,
                    1L, 1L, 0, null, null, null, tooLong);
            fail("超过 128 字符的 sequenceKey 应在入队时直接拒绝，而不是等到发送阶段");
        } catch (IllegalArgumentException expected) {
            // 期望：客户端 publish 立即失败，不落库
        }
        assertEquals("恰好 128 字符应允许",
                128, new OutboxEntry("m1", "T", payload(), OutboxStatus.PENDING,
                        1L, 1L, 0, null, null, null, new String(new char[128]).replace('\0', 'k'))
                        .getSequenceKey().length());
        // 前后空白被裁剪，纯空白归一为 null
        assertEquals("order:1", new OutboxEntry("m1", "T", payload(), OutboxStatus.PENDING,
                1L, 1L, 0, null, null, null, "  order:1  ").getSequenceKey());
        assertEquals(null, new OutboxEntry("m1", "T", payload(), OutboxStatus.PENDING,
                1L, 1L, 0, null, null, null, "   ").getSequenceKey());
    }

    @Test
    public void outboxEntryValidation() {
        assertRejected(new Runnable() {
            public void run() {
                new OutboxEntry(null, "T", payload(), OutboxStatus.PENDING,
                        1L, 1L, 0, null, null, null);
            }
        });
        assertRejected(new Runnable() {
            public void run() {
                new OutboxEntry("m1", " ", payload(), OutboxStatus.PENDING,
                        1L, 1L, 0, null, null, null);
            }
        });
        assertRejected(new Runnable() {
            public void run() {
                new OutboxEntry("m1", "T", null, OutboxStatus.PENDING,
                        1L, 1L, 0, null, null, null);
            }
        });
        assertRejected(new Runnable() {
            public void run() {
                new OutboxEntry("m1", "T", payload(), null,
                        1L, 1L, 0, null, null, null);
            }
        });
    }

    @Test
    public void inboxEntryValidation() {
        assertRejected(new Runnable() {
            public void run() {
                new InboxEntry(" ", "T", payload(), ReceiveStatus.PERSISTED,
                        ProcessStatus.PENDING, 1L, null, 0, null, null);
            }
        });
        assertRejected(new Runnable() {
            public void run() {
                new InboxEntry("m1", "T", payload(), null,
                        ProcessStatus.PENDING, 1L, null, 0, null, null);
            }
        });
        assertRejected(new Runnable() {
            public void run() {
                new InboxEntry("m1", "T", payload(), ReceiveStatus.PERSISTED,
                        null, 1L, null, 0, null, null);
            }
        });
    }

    @Test
    public void outboxWithCreatesUpdatedCopy() {
        OutboxEntry original = new OutboxEntry("m1", "T", payload(), OutboxStatus.FAILED,
                10L, 10L, 1, null, null, "timeout");
        OutboxEntry updated = original.with(OutboxStatus.PENDING, 2, 99L,
                "timeout-2", 20L);

        assertEquals(OutboxStatus.FAILED, original.getStatus());
        assertEquals(1, original.getRetryCount());
        assertEquals(OutboxStatus.PENDING, updated.getStatus());
        assertEquals(2, updated.getRetryCount());
        assertEquals(Long.valueOf(99L), updated.getNextRetryAt());
        assertEquals(20L, updated.getUpdatedAt());
        assertEquals(10L, updated.getCreatedAt());
        assertArrayEquals(original.getPayload(), updated.getPayload());
    }

    @Test
    public void inboxWithKeepsProcessedAtWhenNull() {
        InboxEntry original = new InboxEntry("m1", "T", payload(), ReceiveStatus.PERSISTED,
                ProcessStatus.PROCESSING, 10L, null, 0, null, null);
        InboxEntry updated = original.with(ReceiveStatus.RECEIVED_ACK_SENT,
                ProcessStatus.PROCESSED, 0, null, null, null);

        assertEquals(ReceiveStatus.RECEIVED_ACK_SENT, updated.getReceiveStatus());
        assertEquals(ProcessStatus.PROCESSED, updated.getProcessStatus());
        assertEquals(null, updated.getProcessedAt());
        assertEquals(Long.valueOf(10L), Long.valueOf(updated.getReceivedAt()));
    }

    @Test
    public void inboxWithSetsProcessedAt() {
        InboxEntry original = new InboxEntry("m1", "T", payload(), ReceiveStatus.PERSISTED,
                ProcessStatus.PROCESSING, 10L, null, 0, null, null);
        InboxEntry updated = original.with(ReceiveStatus.RECEIVED_ACK_SENT,
                ProcessStatus.PROCESSED, 0, null, null, 30L);

        assertEquals(Long.valueOf(30L), updated.getProcessedAt());
    }

    private void assertRejected(Runnable construction) {
        try {
            construction.run();
            fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期行为
        }
    }
}
