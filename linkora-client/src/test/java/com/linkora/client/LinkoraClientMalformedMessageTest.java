package com.linkora.client;

import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.transport.http.HttpTransport;
import com.linkora.transport.http.TransportErrorType;
import com.linkora.transport.http.TransportException;
import com.linkora.transport.http.TransportRequest;
import com.linkora.transport.http.TransportResponse;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 下行消息无法解码时的行为：必须回 FAILED，不能静默丢弃让服务端无限重推。
 *
 * @author lianghang
 */
public class LinkoraClientMalformedMessageTest {

    /** 记录发出的请求，不真的走网络。 */
    private static final class RecordingTransport implements HttpTransport {
        final List<TransportRequest> requests = new CopyOnWriteArrayList<TransportRequest>();

        @Override
        public TransportResponse execute(TransportRequest request) throws TransportException {
            requests.add(request);
            throw new TransportException(TransportErrorType.NETWORK_ERROR, "测试桩不发网络请求");
        }

        @Override
        public void close() {
        }

        String findAckBodyContaining(String text) {
            for (TransportRequest request : requests) {
                if (request.getUrl() != null && request.getUrl().endsWith("/acks")) {
                    String body = new String(request.getBody(), StandardCharsets.UTF_8);
                    if (body.contains(text)) {
                        return body;
                    }
                }
            }
            return null;
        }
    }

    @Test
    public void extractMessageIdFromDamagedJson() {
        // 正常
        assertEquals("m-1", LinkoraClient.extractMessageIdLenient(
                "{\"messageId\":\"m-1\",\"clientId\":\"c\"}"));
        // JSON 被截断：messageId 在最前，仍能取到
        assertEquals("m-2", LinkoraClient.extractMessageIdLenient(
                "{\"messageId\":\"m-2\",\"clientId\":\"c\","));
        // 字段间有空白
        assertEquals("m-3", LinkoraClient.extractMessageIdLenient(
                "{ \"messageId\" : \"m-3\" , \"payloadBase64\":\"!!!\" }"));
        // 完全没有 messageId
        assertNull(LinkoraClient.extractMessageIdLenient("{\"clientId\":\"c\"}"));
        // 空 / null
        assertNull(LinkoraClient.extractMessageIdLenient(""));
        assertNull(LinkoraClient.extractMessageIdLenient(null));
        // 超长（>128）不认，避免把畸形串当 ID 回给服务端
        StringBuilder longId = new StringBuilder("\"messageId\":\"");
        for (int i = 0; i < 200; i++) {
            longId.append('x');
        }
        longId.append("\"");
        assertNull(LinkoraClient.extractMessageIdLenient("{" + longId + "}"));

        // ---- 防误取：字段值里伪造的 "messageId" ----
        // traceId 里塞结构化文本，JSON 编码后会转义成 \"messageId\"\"，正则不应命中
        assertEquals("real-1", LinkoraClient.extractMessageIdLenient(
                "{\"messageId\":\"real-1\",\"traceId\":\"x\\\",\\\"messageId\\\":\\\"evil\\\"\"}"));
        // payload 若是明文 JSON（协议外用法），内层 messageId 不得覆盖外层
        assertEquals("real-2", LinkoraClient.extractMessageIdLenient(
                "{\"messageId\":\"real-2\",\"payloadBase64\":\"{}\"}"));
        // 畸形 JSON 里出现重复 messageId：取第一个（编码时序在最前）
        assertEquals("first", LinkoraClient.extractMessageIdLenient(
                "{\"messageId\":\"first\",\"messageId\":\"second\"}"));
        // 值为 null / 空串：不认
        assertNull(LinkoraClient.extractMessageIdLenient("{\"messageId\":null}"));
        assertNull(LinkoraClient.extractMessageIdLenient("{\"messageId\":\"\"}"));
    }

    @Test
    public void malformedMessageTriggersFailedAck() throws Exception {
        RecordingTransport transport = new RecordingTransport();
        Path db = Files.createTempDirectory("linkora-bad-msg").resolve("client.db");
        try (SqliteClientStore store = new SqliteClientStore(db)) {
            LinkoraClient client = LinkoraClient.builder()
                    .serverUrl("http://127.0.0.1:1/linkora")
                    .clientId("c-1")
                    .clientStore(store)
                    .transport(transport)
                    .config(ClientConfig.builder()
                            .senderIntervalMs(100_000)
                            .recoveryIntervalMs(100_000)
                            .build())
                    .build();
            client.start();
            try {
                // payloadBase64 非法 → 解码抛 INVALID_MESSAGE，但 messageId 可提取
                String bad = "{\"messageId\":\"bad-1\",\"clientId\":\"c-1\","
                        + "\"messageType\":\"T\",\"payloadBase64\":\"!!!not-base64!!!\","
                        + "\"createdAt\":1,\"schemaVersion\":1}";
                Method handle = LinkoraClient.class.getDeclaredMethod("handleIncoming", String.class);
                handle.setAccessible(true);
                handle.invoke(client, bad);

                String ackBody = null;
                long deadline = System.currentTimeMillis() + 3000L;
                while (System.currentTimeMillis() < deadline) {
                    ackBody = transport.findAckBodyContaining("bad-1");
                    if (ackBody != null) {
                        break;
                    }
                    Thread.sleep(50L);
                }
                assertTrue("解码失败必须回 FAILED，否则服务端会无限重推", ackBody != null);
                assertTrue("错误码应为 INVALID_MESSAGE，实际: " + ackBody,
                        ackBody.contains("INVALID_MESSAGE"));
            } finally {
                client.close();
            }
        }
    }

    @Test
    public void messageWithoutMessageIdIsDroppedWithoutAck() throws Exception {
        // 连 messageId 都取不到时无法回 ACK，只能依赖服务端 expiresAt —— 断言不崩、也不乱发
        RecordingTransport transport = new RecordingTransport();
        Path db = Files.createTempDirectory("linkora-no-id").resolve("client.db");
        try (SqliteClientStore store = new SqliteClientStore(db)) {
            LinkoraClient client = LinkoraClient.builder()
                    .serverUrl("http://127.0.0.1:1/linkora")
                    .clientId("c-2")
                    .clientStore(store)
                    .transport(transport)
                    .config(ClientConfig.builder()
                            .senderIntervalMs(100_000)
                            .recoveryIntervalMs(100_000)
                            .build())
                    .build();
            client.start();
            try {
                Method handle = LinkoraClient.class.getDeclaredMethod("handleIncoming", String.class);
                handle.setAccessible(true);
                handle.invoke(client, "{\"clientId\":\"c-2\"}");
                Thread.sleep(200L);
                assertNull("没有 messageId 就不该发 ACK", transport.findAckBodyContaining("INVALID_MESSAGE"));
            } finally {
                client.close();
            }
        }
    }
}
