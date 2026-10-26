package com.linkora.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 传输级 payload 加密（AES-256-GCM，0.6.0）。
 *
 * <p>线格式（自描述，解密无需任何配置）：</p>
 * <pre>Base64( IV[12] ‖ AES-256-GCM 密文 ‖ 认证标签[16] )</pre>
 *
 * <p>定位与边界：</p>
 * <ul>
 *   <li>只保护<b>传输与信封</b>：客户端加密、服务端解密后按既有流程处理（落库明文）；</li>
 *   <li>与认证正交：密钥为独立配置项（两端一致），不依赖任何认证方式（匿名部署可用）；</li>
 *   <li>密钥必须<b>带外分发</b>（部署配置/配置中心），绝不在明文线路上在线下发；</li>
 *   <li>密文含认证标签，篡改任一字节解密即失败——失败应走 ackFailed 进重试/死信，
 *       不得静默跳过。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class PayloadCipher {

    /** 密钥长度（字节）：AES-256。 */
    public static final int KEY_LENGTH_BYTES = 32;
    /** GCM IV 长度（字节）。 */
    private static final int IV_LENGTH = 12;
    /** GCM 认证标签长度（位）。 */
    private static final int TAG_BITS = 128;
    /** 默认密钥标识（0.6.0 单 key；轮换协议位已预留 keyId 扩展）。 */
    public static final String DEFAULT_KEY_ID = "k1";
    /** 信封 enc 字段取值（算法:密钥标识）。 */
    public static final String ENC_MARK = "A256GCM:" + DEFAULT_KEY_ID;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PayloadCipher() {
    }

    /** 校验并解码 Base64 配置密钥；不合法时抛 {@link IllegalArgumentException}。 */
    public static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "payload-cipher-key 不能为空（openssl rand -base64 32 生成）");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("payload-cipher-key 不是合法的 Base64", e);
        }
        if (key.length != KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "payload-cipher-key 必须为 " + KEY_LENGTH_BYTES + " 字节（AES-256），当前 "
                            + key.length + " 字节");
        }
        return key;
    }

    /** 加密：返回 Base64(IV ‖ 密文 ‖ Tag)，直接作为信封 payload 字段值。 */
    public static String encryptToBase64(byte[] plaintext, byte[] key) {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plaintext);
            byte[] out = new byte[IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(cipherText, 0, out, IV_LENGTH, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("payload 加密失败", e);
        }
    }

    /** 解密 {@link #encryptToBase64} 的输出；密文被篡改或密钥不符时抛异常。 */
    public static byte[] decryptFromBase64(String base64, byte[] key) {
        byte[] all;
        try {
            all = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("payload 不是合法的 Base64 密文", e);
        }
        if (all.length <= IV_LENGTH) {
            throw new IllegalStateException("密文长度非法");
        }
        byte[] iv = new byte[IV_LENGTH];
        System.arraycopy(all, 0, iv, 0, IV_LENGTH);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(all, IV_LENGTH, all.length - IV_LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("payload 解密失败（密钥不符或密文被篡改）", e);
        }
    }

    /** 生成随机密钥并返回 Base64 形态（运维生成配置用的等价实现）。 */
    public static String randomKeyBase64() {
        byte[] key = new byte[KEY_LENGTH_BYTES];
        RANDOM.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** 便捷重载：String 明文 → Base64 密文。 */
    public static String encryptToBase64(String plaintext, byte[] key) {
        return encryptToBase64(plaintext.getBytes(StandardCharsets.UTF_8), key);
    }
}
