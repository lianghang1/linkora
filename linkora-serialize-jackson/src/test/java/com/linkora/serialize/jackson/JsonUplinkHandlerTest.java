package com.linkora.serialize.jackson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * {@link JsonUplinkHandler} 单元测试：JSON 反序列化分发、解析失败兜底。
 *
 * @author lianghang
 */
public class JsonUplinkHandlerTest {

    /** 测试 payload DTO（公共字段，Jackson 可直接绑定）。 */
    public static final class DemoPayload {
        public String orderNo;
        public int count;
    }

    private static LinkoraMessage message(byte[] payload) {
        return LinkoraMessage.builder()
                .messageId("m-1")
                .clientId("client-1")
                .messageType("demo")
                .payload(payload)
                .createdAt(System.currentTimeMillis())
                .schemaVersion(1)
                .build();
    }

    @Test
    public void deserializesPayloadAndDispatchesToSubclass() {
        final AtomicReference<DemoPayload> received = new AtomicReference<DemoPayload>();
        final AtomicReference<LinkoraMessage> receivedMessage = new AtomicReference<LinkoraMessage>();
        JsonUplinkHandler<DemoPayload> handler = new JsonUplinkHandler<DemoPayload>(DemoPayload.class) {
            @Override
            public String messageType() {
                return "demo";
            }

            @Override
            protected void handle(LinkoraMessage message, DemoPayload payload) {
                receivedMessage.set(message);
                received.set(payload);
            }
        };
        byte[] raw = "{\"orderNo\":\"SO-1001\",\"count\":3}".getBytes(StandardCharsets.UTF_8);

        handler.handle(message(raw));

        assertSame("demo", handler.messageType());
        assertSame(DemoPayload.class, handler.payloadType());
        assertEquals("SO-1001", received.get().orderNo);
        assertEquals(3, received.get().count);
        assertEquals("m-1", receivedMessage.get().getMessageId());
    }

    @Test
    public void deserializesCollectionPayloadViaTypeReference() {
        final AtomicReference<List<DemoPayload>> received = new AtomicReference<List<DemoPayload>>();
        JsonUplinkHandler<List<DemoPayload>> handler =
                new JsonUplinkHandler<List<DemoPayload>>(new TypeReference<List<DemoPayload>>() { }) {
            @Override
            public String messageType() {
                return "demo-batch";
            }

            @Override
            protected void handle(LinkoraMessage message, List<DemoPayload> payload) {
                received.set(payload);
            }
        };
        byte[] raw = "[{\"orderNo\":\"SO-1\",\"count\":1},{\"orderNo\":\"SO-2\",\"count\":2}]"
                .getBytes(StandardCharsets.UTF_8);

        handler.handle(message(raw));

        assertEquals(2, received.get().size());
        assertEquals("SO-1", received.get().get(0).orderNo);
        assertEquals(2, received.get().get(1).count);
    }

    @Test
    public void malformedPayloadGoesToParseErrorInsteadOfHandle() {
        final AtomicInteger handleCalls = new AtomicInteger();
        final AtomicInteger parseErrorCalls = new AtomicInteger();
        final AtomicReference<String> parseErrorMessageId = new AtomicReference<String>();
        JsonUplinkHandler<DemoPayload> handler = new JsonUplinkHandler<DemoPayload>(DemoPayload.class) {
            @Override
            public String messageType() {
                return "demo";
            }

            @Override
            protected void handle(LinkoraMessage message, DemoPayload payload) {
                handleCalls.incrementAndGet();
            }

            @Override
            protected void onParseError(LinkoraMessage message, LinkoraException cause) {
                parseErrorCalls.incrementAndGet();
                parseErrorMessageId.set(message.getMessageId());
            }
        };

        handler.handle(message("not-json".getBytes(StandardCharsets.UTF_8)));
        handler.handle(message(new byte[0]));

        assertEquals("handle 不应被调用", 0, handleCalls.get());
        assertEquals("非法 JSON 与空 payload 各触发一次兜底", 2, parseErrorCalls.get());
        assertEquals("m-1", parseErrorMessageId.get());
    }
}
