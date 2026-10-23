package com.linkora.serialize.jackson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.AckType;
import com.linkora.core.message.LinkoraMessage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Linkora 线路格式编解码（消息信封 / ACK / 服务端应答），JSON 实现。
 *
 * <p>线路格式（payload 使用 Base64 承载任意字节）：</p>
 * <pre>
 * 消息：{"messageId":"..","clientId":"..","messageType":"..","payloadBase64":"..",
 *        "createdAt":1,"expiresAt":null,"schemaVersion":1,"traceId":null}
 * ACK：{"messageId":"..","ackType":"RECEIVED|PROCESSED|FAILED","errorCode":null,"errorMessage":null}
 * 摄入应答：{"status":"ACCEPTED|DUPLICATE","messageId":".."}
 * ACK 应答：{"status":"OK"}
 * </pre>
 *
 * <p>字段缺失/类型不符抛出 {@link LinkoraException}（INVALID_MESSAGE），由调用方
 * 转换为对应 HTTP 状态。实例线程安全。</p>
 *
 * @author lianghang
 */
public final class MessageJsonCodec {

    // ---------------- 消息信封 ----------------

    /** 将消息编码为线路 JSON 字符串。 */
    public String encodeMessage(LinkoraMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为 null");
        }
        ObjectNode node = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        node.put("messageId", message.getMessageId());
        node.put("clientId", message.getClientId());
        node.put("messageType", message.getMessageType());
        node.put("payloadBase64", Base64.getEncoder().encodeToString(message.getPayload()));
        node.put("createdAt", message.getCreatedAt());
        if (message.getExpiresAt() != null) {
            node.put("expiresAt", message.getExpiresAt().longValue());
        } else {
            node.putNull("expiresAt");
        }
        node.put("schemaVersion", message.getSchemaVersion());
        if (message.getTraceId() != null) {
            node.put("traceId", message.getTraceId());
        } else {
            node.putNull("traceId");
        }
        // 顺序键为可选字段：只在有值时输出；解码按字段名读、未知字段忽略，
        // 老版本收到带 sequenceKey 的消息不会报错（滚动升级安全）
        if (message.getSequenceKey() != null) {
            node.put("sequenceKey", message.getSequenceKey());
        }
        return node.toString();
    }

    /** 解码线路 JSON 为消息；结构非法抛出 INVALID_MESSAGE。 */
    public LinkoraMessage decodeMessage(String json) {
        JsonNode node = parse(json);
        LinkoraMessage.Builder builder = LinkoraMessage.builder()
                .messageId(requireText(node, "messageId"))
                .clientId(requireText(node, "clientId"))
                .messageType(requireText(node, "messageType"))
                .payload(decodePayload(node))
                .createdAt(requireLong(node, "createdAt"))
                .schemaVersion(requireInt(node, "schemaVersion"));
        JsonNode expiresAt = node.get("expiresAt");
        if (expiresAt != null && !expiresAt.isNull()) {
            builder.expiresAt(longValue(expiresAt, "expiresAt"));
        }
        JsonNode traceId = node.get("traceId");
        if (traceId != null && !traceId.isNull()) {
            builder.traceId(traceId.asText());
        }
        JsonNode sequenceKey = node.get("sequenceKey");
        if (sequenceKey != null && !sequenceKey.isNull()) {
            builder.sequenceKey(sequenceKey.asText());
        }
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE,
                    "消息字段校验失败: " + e.getMessage(), e);
        }
    }

    private byte[] decodePayload(JsonNode node) {
        JsonNode payload = node.get("payloadBase64");
        if (payload == null || payload.isNull() || payload.asText().isEmpty()) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "缺少 payloadBase64 字段");
        }
        try {
            return Base64.getDecoder().decode(payload.asText());
        } catch (IllegalArgumentException e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "payloadBase64 不是合法 Base64", e);
        }
    }

    // ---------------- ACK ----------------

    /** 编码客户端 ACK 请求体。 */
    public String encodeAck(String messageId, AckType ackType, String errorCode, String errorMessage) {
        if (messageId == null || messageId.trim().isEmpty() || ackType == null) {
            throw new IllegalArgumentException("messageId 与 ackType 不能为空");
        }
        ObjectNode node = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        node.put("messageId", messageId);
        node.put("ackType", ackType.name());
        if (errorCode != null) {
            node.put("errorCode", errorCode);
        } else {
            node.putNull("errorCode");
        }
        if (errorMessage != null) {
            node.put("errorMessage", errorMessage);
        } else {
            node.putNull("errorMessage");
        }
        return node.toString();
    }

    /** 解码 ACK 请求体。 */
    public WireAck decodeAck(String json) {
        JsonNode node = parse(json);
        String messageId = requireText(node, "messageId");
        String ackTypeText = requireText(node, "ackType");
        AckType ackType;
        try {
            ackType = AckType.valueOf(ackTypeText);
        } catch (IllegalArgumentException e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE,
                    "未知 ackType: " + ackTypeText, e);
        }
        return new WireAck(messageId, ackType,
                optionalText(node, "errorCode"), optionalText(node, "errorMessage"));
    }

    // ---------------- 服务端应答 ----------------

    /** 编码摄入（上行上报）应答：status = ACCEPTED / DUPLICATE。 */
    public String encodeIngestResponse(boolean duplicate, String messageId) {
        ObjectNode node = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        node.put("status", duplicate ? "DUPLICATE" : "ACCEPTED");
        node.put("messageId", messageId);
        return node.toString();
    }

    /** 解码摄入应答；返回 true 表示服务端已持久化（含幂等重复命中）。 */
    public boolean isIngestAccepted(byte[] responseBody) {
        JsonNode node = parseBytes(responseBody);
        String status = requireText(node, "status");
        return "ACCEPTED".equals(status) || "DUPLICATE".equals(status);
    }

    /** 编码 ACK 应答。 */
    public String encodeAckResponse() {
        ObjectNode node = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        node.put("status", "OK");
        return node.toString();
    }

    /** 校验 ACK 应答是否为 OK。 */
    public boolean isAckResponseOk(byte[] responseBody) {
        JsonNode node = parseBytes(responseBody);
        return "OK".equals(requireText(node, "status"));
    }

    /** 编码统一错误应答体（配合对应 HTTP 状态码使用）。 */
    public String encodeErrorResponse(LinkoraErrorCode errorCode, String errorMessage) {
        ObjectNode node = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        node.put("errorCode", errorCode == null ? LinkoraErrorCode.INTERNAL_ERROR.getCode() : errorCode.getCode());
        node.put("errorMessage", errorMessage == null ? "" : errorMessage);
        return node.toString();
    }

    // ---------------- 内部工具 ----------------

    private JsonNode parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "请求体为空");
        }
        try {
            return LinkoraJsonUtil.getObjectMapper().readTree(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "JSON 解析失败", e);
        }
    }

    private JsonNode parseBytes(byte[] body) {
        if (body == null || body.length == 0) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "响应体为空");
        }
        try {
            return LinkoraJsonUtil.getObjectMapper().readTree(body);
        } catch (Exception e) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "响应 JSON 解析失败", e);
        }
    }

    private static String requireText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().trim().isEmpty()) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "缺少字段: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static long requireLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "缺少字段: " + field);
        }
        return longValue(value, field);
    }

    private static long longValue(JsonNode value, String field) {
        if (!value.canConvertToLong()) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "字段不是整数: " + field);
        }
        return value.asLong();
    }

    private static int requireInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new LinkoraException(LinkoraErrorCode.INVALID_MESSAGE, "字段不是整数: " + field);
        }
        return value.asInt();
    }

    /** ACK 请求体模型。 */
    public static final class WireAck {
        private final String messageId;
        private final AckType ackType;
        private final String errorCode;
        private final String errorMessage;

        public WireAck(String messageId, AckType ackType, String errorCode, String errorMessage) {
            this.messageId = messageId;
            this.ackType = ackType;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        public String getMessageId() {
            return messageId;
        }

        public AckType getAckType() {
            return ackType;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
