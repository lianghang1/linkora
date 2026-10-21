package com.linkora.core.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 签名算法（纯 JDK 实现，客户端与服务端共享，保证两侧字节级一致）：
 *
 * <pre>
 * bodyHash   = SHA-256(body).hexLower          （body 为空时对空字节数组求哈希）
 * canonical  = clientId + "\n" + HTTP方法 + "\n" + timestampMillis + "\n" + nonce + "\n" + bodyHash
 * signature  = HMAC-SHA256(secretBytes, canonical).hexLower
 * </pre>
 *
 * <p>设计说明：签名不包含 URL 路径，避免 Servlet 容器对 contextPath/servletPath
 * 的映射差异导致校验失败；跨端点重放由 timestamp + nonce 缓存拦截。</p>
 *
 * @author lianghang
 */
public final class HmacSha256 {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacSha256() {
    }

    /** HMAC-SHA256 签名（hex 小写）。 */
    public static String sign(byte[] secret, String canonical) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret 不能为空");
        }
        if (canonical == null) {
            throw new IllegalArgumentException("canonical 不能为 null");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return hex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("secret 不是合法的 HMAC 密钥", e);
        }
    }

    /** SHA-256 摘要（hex 小写）。 */
    public static String sha256Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(
                    data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /** 规范串组装（客户端与服务端唯一权威实现）。 */
    public static String canonical(String clientId, String method, long timestampMillis,
                                   String nonce, byte[] body) {
        return clientId + "\n"
                + (method == null ? "" : method) + "\n"
                + timestampMillis + "\n"
                + (nonce == null ? "" : nonce) + "\n"
                + sha256Hex(body);
    }

    /** 恒定时间比较，防时序侧信道。 */
    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成高熵访问令牌（256 bit，Base64URL 无填充）。 */
    public static String generateToken() {
        byte[] token = new byte[32];
        new java.security.SecureRandom().nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    /** 生成一次性随机数（128 bit，hex）。 */
    public static String generateNonce() {
        byte[] nonce = new byte[16];
        new java.security.SecureRandom().nextBytes(nonce);
        return hex(nonce);
    }

    private static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
