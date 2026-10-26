package com.linkora.core.crypto;

import com.linkora.core.message.LinkoraMessage;

/**
 * {@link LinkoraMessage} 的 payload 加解密便捷封装（0.6.0）。
 *
 * <p>加密只作用于 payload 字段（信封其余元数据明文），并写入 {@code enc}
 * 标记（{@code A256GCM:keyId}）供接收端识别；解密按 enc 标记执行，
 * 密文被篡改时抛 {@link IllegalStateException}（调用方应转 ACK FAILED 进重试/死信）。</p>
 *
 * @author lianghang
 */
public final class EncryptedMessages {

    private EncryptedMessages() {
    }

    /** 加密 payload 并设置 enc 标记；已带 enc 的消息原样返回（幂等）。 */
    public static LinkoraMessage encrypt(byte[] key, LinkoraMessage message) {
        if (message.getEnc() != null) {
            return message;
        }
        String cipherBase64 = PayloadCipher.encryptToBase64(message.getPayload(), key);
        return copy(message, cipherBase64.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                PayloadCipher.ENC_MARK);
    }

    /** 按 enc 标记解密 payload；无标记原样返回。失败抛 IllegalStateException。 */
    public static LinkoraMessage decrypt(byte[] key, LinkoraMessage message) {
        if (message.getEnc() == null) {
            return message;
        }
        byte[] plain = PayloadCipher.decryptFromBase64(
                new String(message.getPayload(), java.nio.charset.StandardCharsets.UTF_8), key);
        return copy(message, plain, null);
    }

    private static LinkoraMessage copy(LinkoraMessage m, byte[] payload, String enc) {
        LinkoraMessage.Builder b = new LinkoraMessage.Builder()
                .messageId(m.getMessageId())
                .clientId(m.getClientId())
                .messageType(m.getMessageType())
                .payload(payload)
                .createdAt(m.getCreatedAt())
                .expiresAt(m.getExpiresAt())
                .schemaVersion(m.getSchemaVersion())
                .traceId(m.getTraceId())
                .sequenceKey(m.getSequenceKey())
                .subject(m.getSubject());
        if (enc != null) {
            b.enc(enc);
        }
        return b.build();
    }
}
