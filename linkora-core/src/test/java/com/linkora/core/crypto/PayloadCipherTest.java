package com.linkora.core.crypto;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

/**
 * PayloadCipher 冒烟：加解密往返、防篡改、密钥校验、随机 IV 语义。
 *
 * @author lianghang
 */
public class PayloadCipherTest {

    private final byte[] key = PayloadCipher.decodeKey(PayloadCipher.randomKeyBase64());

    @Test
    public void roundTrip() {
        String plain = "{\"tomb_code\":\"A-001\",\"name\":\"测试墓位\"}";
        String cipher = PayloadCipher.encryptToBase64(plain, key);
        assertNotEquals(plain, cipher);
        byte[] out = PayloadCipher.decryptFromBase64(cipher, key);
        assertEquals(plain, new String(out, StandardCharsets.UTF_8));
    }

    @Test
    public void tamperDetection() {
        String cipher = PayloadCipher.encryptToBase64("hello", key);
        byte[] raw = java.util.Base64.getDecoder().decode(cipher);
        raw[raw.length - 1] ^= 0x01; // 翻转末位
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);
        try {
            PayloadCipher.decryptFromBase64(tampered, key);
            fail("篡改后应解密失败");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void wrongKeyFails() {
        byte[] otherKey = PayloadCipher.decodeKey(PayloadCipher.randomKeyBase64());
        String cipher = PayloadCipher.encryptToBase64("hello", key);
        try {
            PayloadCipher.decryptFromBase64(cipher, otherKey);
            fail("错误密钥应解密失败");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void randomIvMakesCiphertextNonDeterministic() {
        String cipher1 = PayloadCipher.encryptToBase64("same", key);
        String cipher2 = PayloadCipher.encryptToBase64("same", key);
        assertNotEquals(cipher1, cipher2, "随机 IV：相同明文两次加密应不同");
        assertEquals("same", new String(
                PayloadCipher.decryptFromBase64(cipher2, key), StandardCharsets.UTF_8));
    }

    @Test
    public void keyValidation() {
        try {
            PayloadCipher.decodeKey(null);
            fail("null key 应被拒绝");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PayloadCipher.decodeKey("");
            fail("空 key 应被拒绝");
        } catch (IllegalArgumentException expected) {
        }
        try {
            PayloadCipher.decodeKey(java.util.Base64.getEncoder().encodeToString(new byte[16]));
            fail("16 字节 key 应被拒绝");
        } catch (IllegalArgumentException expected) {
        }
        assertArrayEquals(key, PayloadCipher.decodeKey(PayloadCipher.randomKeyBase64()).length == 32
                ? key : new byte[0]); // 自检：randomKey 长度路径
    }

    @Test
    public void encMarkFormat() {
        assertEquals("A256GCM:k1", PayloadCipher.ENC_MARK);
    }
}
