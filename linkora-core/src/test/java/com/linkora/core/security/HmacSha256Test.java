package com.linkora.core.security;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 用标准测试向量与字段差异比较，验证签名算法输出的正确性与确定性。
 *
 * @author lianghang
 */
public class HmacSha256Test {

    @Test
    public void sha256HexKnownVector() {
        // SHA-256("abc") 标准测试向量
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HmacSha256.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
        // 空输入
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                HmacSha256.sha256Hex(new byte[0]));
    }

    @Test
    public void signIsDeterministicAndHexLowercase() {
        byte[] secret = "secret-key".getBytes(StandardCharsets.UTF_8);
        String a = HmacSha256.sign(secret, "canonical-1");
        String b = HmacSha256.sign(secret, "canonical-1");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertEquals(a, a.toLowerCase());
        assertNotEquals(a, HmacSha256.sign(secret, "canonical-2"));
        assertNotEquals(a, HmacSha256.sign("other".getBytes(StandardCharsets.UTF_8), "canonical-1"));
    }

    @Test
    public void canonicalIncludesAllFields() {
        byte[] secret = "k".getBytes(StandardCharsets.UTF_8);
        String base = HmacSha256.sign(secret,
                HmacSha256.canonical("c1", "POST", 1000L, "n1", "body".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(base, HmacSha256.sign(secret,
                HmacSha256.canonical("c2", "POST", 1000L, "n1", "body".getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(base, HmacSha256.sign(secret,
                HmacSha256.canonical("c1", "GET", 1000L, "n1", "body".getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(base, HmacSha256.sign(secret,
                HmacSha256.canonical("c1", "POST", 1001L, "n1", "body".getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(base, HmacSha256.sign(secret,
                HmacSha256.canonical("c1", "POST", 1000L, "n2", "body".getBytes(StandardCharsets.UTF_8))));
        assertNotEquals(base, HmacSha256.sign(secret,
                HmacSha256.canonical("c1", "POST", 1000L, "n1", "body2".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void constantTimeEquals() {
        assertTrue(HmacSha256.constantTimeEquals("abc", "abc"));
        assertFalse(HmacSha256.constantTimeEquals("abc", "abd"));
        assertFalse(HmacSha256.constantTimeEquals("abc", null));
        assertFalse(HmacSha256.constantTimeEquals(null, "abc"));
    }

    @Test
    public void generatedTokensAreUniqueAndHighEntropy() {
        String t1 = HmacSha256.generateToken();
        String t2 = HmacSha256.generateToken();
        assertNotEquals(t1, t2);
        assertEquals(43, t1.length()); // 32 字节 Base64URL 无填充
        assertTrue(HmacSha256.generateNonce().matches("[0-9a-f]{32}"));
    }
}
