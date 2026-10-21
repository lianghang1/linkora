package com.linkora.client;

import com.linkora.core.security.HmacSha256;
import com.linkora.core.security.SecurityHeaders;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class HmacCredentialProviderTest {

    @Test
    public void headersContainAllSecurityFieldsWithValidSignature() {
        AtomicLong clock = new AtomicLong(1700000000000L);
        byte[] secret = "k".getBytes(StandardCharsets.UTF_8);
        HmacCredentialProvider provider = new HmacCredentialProvider(
                "store-001", "token-1", secret, clock::get);
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        Map<String, String> headers = provider.authHeaders(
                new CredentialProvider.RequestMaterial("POST", body));

        assertEquals("token-1", headers.get(SecurityHeaders.ACCESS_TOKEN));
        assertEquals("1700000000000", headers.get(SecurityHeaders.TIMESTAMP));
        assertTrue(headers.get(SecurityHeaders.NONCE).matches("[0-9a-f]{32}"));
        String expected = HmacSha256.sign(secret, HmacSha256.canonical(
                "store-001", "POST", clock.get(), headers.get(SecurityHeaders.NONCE), body));
        assertEquals(expected, headers.get(SecurityHeaders.SIGNATURE));
    }

    @Test
    public void nonceAndSignatureVaryPerRequest() {
        AtomicLong clock = new AtomicLong(1000L);
        HmacCredentialProvider provider = new HmacCredentialProvider(
                "c", "t", "s".getBytes(StandardCharsets.UTF_8), clock::get);
        CredentialProvider.RequestMaterial material =
                new CredentialProvider.RequestMaterial("POST", new byte[0]);
        Map<String, String> first = provider.authHeaders(material);
        Map<String, String> second = provider.authHeaders(material);
        assertNotEquals(first.get(SecurityHeaders.NONCE), second.get(SecurityHeaders.NONCE));
        assertNotEquals(first.get(SecurityHeaders.SIGNATURE), second.get(SecurityHeaders.SIGNATURE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptySecretRejected() {
        new HmacCredentialProvider("c", "t", new byte[0]);
    }
}
