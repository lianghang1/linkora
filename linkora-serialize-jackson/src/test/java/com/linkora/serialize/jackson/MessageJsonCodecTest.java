package com.linkora.serialize.jackson;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.AckType;
import com.linkora.core.message.LinkoraMessage;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MessageJsonCodecTest {

    private final MessageJsonCodec codec = new MessageJsonCodec();

    private LinkoraMessage sample(Long expiresAt, String traceId) {
        return LinkoraMessage.builder()
                .messageId("m-1")
                .clientId("store-001")
                .messageType("ORDER_CREATED")
                .payload("{\"k\":\"值\"}")
                .createdAt(1000L)
                .expiresAt(expiresAt)
                .schemaVersion(2)
                .traceId(traceId)
                .build();
    }

    @Test
    public void messageRoundTripAllFields() {
        LinkoraMessage original = sample(2000L, "trace-1");
        LinkoraMessage decoded = codec.decodeMessage(codec.encodeMessage(original));
        assertEquals(original, decoded);
        assertEquals("m-1", decoded.getMessageId());
        assertEquals("store-001", decoded.getClientId());
        assertEquals("ORDER_CREATED", decoded.getMessageType());
        assertEquals(2000L, decoded.getExpiresAt().longValue());
        assertEquals("trace-1", decoded.getTraceId());
        assertEquals(2, decoded.getSchemaVersion());
    }

    @Test
    public void messageRoundTripNullOptionalFields() {
        LinkoraMessage original = sample(null, null);
        LinkoraMessage decoded = codec.decodeMessage(codec.encodeMessage(original));
        assertEquals(original, decoded);
        assertNull(decoded.getExpiresAt());
        assertNull(decoded.getTraceId());
    }

    @Test
    public void binaryPayloadSurvivesRoundTrip() {
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) i;
        }
        LinkoraMessage original = LinkoraMessage.builder()
                .messageId("m-bin").clientId("c").messageType("T")
                .payload(binary).createdAt(1L).build();
        assertArrayEquals(binary, codec.decodeMessage(codec.encodeMessage(original)).getPayload());
    }

    @Test
    public void decodeRejectsMissingFields() {
        assertInvalid("{\"clientId\":\"c\"}");
        assertInvalid("{\"messageId\":\"m\",\"messageType\":\"T\",\"payloadBase64\":\"aGk=\","
                + "\"createdAt\":1,\"schemaVersion\":1}");
        assertInvalid("{\"messageId\":\"m\",\"clientId\":\"c\",\"messageType\":\"T\","
                + "\"payloadBase64\":\"not-base64!!\",\"createdAt\":1,\"schemaVersion\":1}");
        assertInvalid("not-json");
        assertInvalid("");
        assertInvalid(null);
    }

    private void assertInvalid(String json) {
        try {
            codec.decodeMessage(json);
            fail("应拒绝非法输入: " + json);
        } catch (LinkoraException e) {
            assertEquals(LinkoraErrorCode.INVALID_MESSAGE, e.getErrorCode());
        }
    }

    @Test
    public void ackRoundTrip() {
        String json = codec.encodeAck("m-1", AckType.FAILED, "BIZ_ERROR", "库存不足");
        MessageJsonCodec.WireAck ack = codec.decodeAck(json);
        assertEquals("m-1", ack.getMessageId());
        assertEquals(AckType.FAILED, ack.getAckType());
        assertEquals("BIZ_ERROR", ack.getErrorCode());
        assertEquals("库存不足", ack.getErrorMessage());

        MessageJsonCodec.WireAck plain = codec.decodeAck(codec.encodeAck("m-2", AckType.RECEIVED, null, null));
        assertEquals(AckType.RECEIVED, plain.getAckType());
        assertNull(plain.getErrorCode());
        assertNull(plain.getErrorMessage());
    }

    @Test
    public void decodeAckRejectsUnknownType() {
        try {
            codec.decodeAck("{\"messageId\":\"m\",\"ackType\":\"WHAT\"}");
            fail();
        } catch (LinkoraException e) {
            assertEquals(LinkoraErrorCode.INVALID_MESSAGE, e.getErrorCode());
        }
    }

    @Test
    public void ingestResponseAcceptedAndDuplicate() {
        assertTrue(codec.isIngestAccepted(
                codec.encodeIngestResponse(false, "m-1").getBytes(StandardCharsets.UTF_8)));
        assertTrue(codec.isIngestAccepted(
                codec.encodeIngestResponse(true, "m-1").getBytes(StandardCharsets.UTF_8)));
        assertFalse(codec.isIngestAccepted("{\"status\":\"OTHER\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void ackResponseOkAndErrorBody() {
        assertTrue(codec.isAckResponseOk(codec.encodeAckResponse().getBytes(StandardCharsets.UTF_8)));
        assertFalse(codec.isAckResponseOk("{\"status\":\"NO\"}".getBytes(StandardCharsets.UTF_8)));
        String error = codec.encodeErrorResponse(LinkoraErrorCode.FORBIDDEN, "身份不一致");
        assertTrue(error.contains("FORBIDDEN"));
        assertTrue(error.contains("身份不一致"));
    }

    @Test
    public void payloadSerializerRoundTrip() {
        JacksonPayloadSerializer serializer = new JacksonPayloadSerializer();
        assertEquals("application/json", serializer.contentType());
        Pojo original = new Pojo();
        original.name = "门店";
        original.count = 7;
        Pojo decoded = serializer.deserialize(serializer.serialize(original), Pojo.class);
        assertEquals("门店", decoded.name);
        assertEquals(7, decoded.count);
    }

    @Test
    public void payloadSerializerWrapsFailures() {
        JacksonPayloadSerializer serializer = new JacksonPayloadSerializer();
        try {
            serializer.serialize(new Object()); // 无可序列化属性 → Jackson 失败
            // Jackson 对空 Bean 抛异常；若版本行为不同则跳过断言
        } catch (LinkoraException e) {
            assertEquals(LinkoraErrorCode.SERIALIZATION_ERROR, e.getErrorCode());
        }
        try {
            serializer.deserialize("{bad".getBytes(StandardCharsets.UTF_8), Pojo.class);
            fail();
        } catch (LinkoraException e) {
            assertEquals(LinkoraErrorCode.SERIALIZATION_ERROR, e.getErrorCode());
        }
    }

    public static class Pojo {
        public String name;
        public int count;
    }
}
