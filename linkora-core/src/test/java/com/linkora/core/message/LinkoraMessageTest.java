package com.linkora.core.message;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class LinkoraMessageTest {

    private LinkoraMessage.Builder validBuilder() {
        return LinkoraMessage.builder()
                .messageId("m-1")
                .clientId("store-001")
                .messageType("ORDER_CREATED")
                .payload("{\"a\":1}")
                .createdAt(1000L);
    }

    @Test
    public void buildWithDefaults() {
        LinkoraMessage message = validBuilder().build();

        assertEquals("m-1", message.getMessageId());
        assertEquals("store-001", message.getClientId());
        assertEquals("ORDER_CREATED", message.getMessageType());
        assertEquals(1000L, message.getCreatedAt());
        assertEquals(1, message.getSchemaVersion());
        assertNull(message.getExpiresAt());
        assertNull(message.getTraceId());
        assertEquals("{\"a\":1}", message.getPayloadAsUtf8String());
    }

    @Test
    public void blankRequiredFieldsRejected() {
        assertBlankRejected(validBuilder().messageId("  "));
        assertBlankRejected(validBuilder().messageId(null));
        assertBlankRejected(validBuilder().clientId(""));
        assertBlankRejected(validBuilder().messageType(null));
    }

    private void assertBlankRejected(LinkoraMessage.Builder builder) {
        try {
            builder.build();
            fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期行为
        }
    }

    @Test
    public void nullPayloadRejected() {
        try {
            validBuilder().payload((byte[]) null).build();
            fail("预期抛出 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期行为
        }
    }

    @Test
    public void invalidTimestampsRejected() {
        try {
            validBuilder().createdAt(0).build();
            fail("createdAt=0 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期行为
        }
        try {
            validBuilder().expiresAt(1000L).build();
            fail("expiresAt <= createdAt 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期行为
        }
    }

    @Test
    public void payloadIsDefensivelyCopied() {
        byte[] raw = "abc".getBytes(StandardCharsets.UTF_8);
        LinkoraMessage message = validBuilder().payload(raw).build();
        raw[0] = 'X';

        assertEquals("abc", message.getPayloadAsUtf8String());

        byte[] fetched = message.getPayload();
        fetched[0] = 'Y';
        assertEquals("abc", message.getPayloadAsUtf8String());
    }

    @Test
    public void equalityAndHashCode() {
        LinkoraMessage a = validBuilder().traceId("t1").build();
        LinkoraMessage b = validBuilder().traceId("t1").build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertArrayEquals(a.getPayload(), b.getPayload());
    }

    @Test
    public void toStringHidesPayload() {
        LinkoraMessage message = validBuilder().payload("secret-data").build();
        String text = message.toString();

        assertEquals(false, text.contains("secret-data"));
        assertEquals(true, text.contains("payloadSize=" + message.getPayloadSize()));
    }
}
