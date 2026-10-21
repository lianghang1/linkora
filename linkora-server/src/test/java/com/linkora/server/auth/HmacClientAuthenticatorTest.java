package com.linkora.server.auth;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.security.HmacSha256;
import com.linkora.core.security.SecurityHeaders;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HmacClientAuthenticatorTest {

    private static final String CLIENT = "store-001";
    private static final String TOKEN = "test-token-123";
    private static final byte[] SECRET = "test-secret-key".getBytes(StandardCharsets.UTF_8);

    private MapCredentialStore credentialStore;
    private HmacClientAuthenticator authenticator;

    @Before
    public void setUp() {
        credentialStore = new MapCredentialStore();
        credentialStore.put(CLIENT, TOKEN, SECRET);
        // 锁定阈值 2 次、基础锁定 50ms，便于测试；时钟窗口 60s
        authenticator = new HmacClientAuthenticator(credentialStore, 60_000L,
                new FailureLockout(2, 50L, 500L), new NonceCache(120_000L, 1000));
    }

    private AuthResult auth(String token, String method, long timestamp, String nonce,
                            byte[] body, byte[] secret) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(SecurityHeaders.ACCESS_TOKEN, token);
        headers.put(SecurityHeaders.TIMESTAMP, String.valueOf(timestamp));
        headers.put(SecurityHeaders.NONCE, nonce);
        headers.put(SecurityHeaders.SIGNATURE, HmacSha256.sign(secret,
                HmacSha256.canonical(CLIENT, method, timestamp, nonce, body)));
        return authenticator.authenticate(new AuthRequest(CLIENT, method, headers, body));
    }

    @Test
    public void validRequestAccepted() {
        long now = System.currentTimeMillis();
        AuthResult result = auth(TOKEN, "POST", now,
                HmacSha256.generateNonce(), "payload".getBytes(StandardCharsets.UTF_8), SECRET);
        assertTrue(result.isAuthenticated());
    }

    @Test
    public void sseGetRequestAccepted() {
        long now = System.currentTimeMillis();
        AuthResult result = auth(TOKEN, "GET", now,
                HmacSha256.generateNonce(), null, SECRET);
        assertTrue(result.isAuthenticated());
    }

    @Test
    public void missingIdentityRejected() {
        AuthResult result = authenticator.authenticate(
                new AuthRequest(null, "POST", new LinkedHashMap<String, String>(), new byte[0]));
        assertFalse(result.isAuthenticated());
        assertEquals(LinkoraErrorCode.AUTH_REQUIRED, result.getErrorCode());
    }

    @Test
    public void unknownClientRejected() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        AuthResult result = authenticator.authenticate(
                new AuthRequest("ghost", "POST", headers, new byte[0]));
        assertFalse(result.isAuthenticated());
        assertEquals(LinkoraErrorCode.AUTH_FAILED, result.getErrorCode());
    }

    @Test
    public void wrongTokenRejected() {
        long now = System.currentTimeMillis();
        AuthResult result = auth("wrong-token", "POST", now,
                HmacSha256.generateNonce(), "p".getBytes(StandardCharsets.UTF_8), SECRET);
        assertFalse(result.isAuthenticated());
        assertEquals(LinkoraErrorCode.AUTH_FAILED, result.getErrorCode());
    }

    @Test
    public void wrongSecretRejected() {
        long now = System.currentTimeMillis();
        AuthResult result = auth(TOKEN, "POST", now,
                HmacSha256.generateNonce(), "p".getBytes(StandardCharsets.UTF_8),
                "attacker-key".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isAuthenticated());
    }

    @Test
    public void staleTimestampRejected() {
        long old = System.currentTimeMillis() - 120_000L;
        AuthResult result = auth(TOKEN, "POST", old,
                HmacSha256.generateNonce(), "p".getBytes(StandardCharsets.UTF_8), SECRET);
        assertFalse(result.isAuthenticated());
    }

    @Test
    public void replayedNonceRejected() {
        long now = System.currentTimeMillis();
        String nonce = HmacSha256.generateNonce();
        byte[] body = "p".getBytes(StandardCharsets.UTF_8);
        assertTrue(auth(TOKEN, "POST", now, nonce, body, SECRET).isAuthenticated());
        AuthResult replay = auth(TOKEN, "POST", now, nonce, body, SECRET);
        assertFalse("相同 nonce 的重放应被拒绝", replay.isAuthenticated());
    }

    @Test
    public void bodyTamperingDetected() {
        long now = System.currentTimeMillis();
        String nonce = HmacSha256.generateNonce();
        // 用 body="a" 计算签名后提交 body="b"
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(SecurityHeaders.ACCESS_TOKEN, TOKEN);
        headers.put(SecurityHeaders.TIMESTAMP, String.valueOf(now));
        headers.put(SecurityHeaders.NONCE, nonce);
        headers.put(SecurityHeaders.SIGNATURE, HmacSha256.sign(SECRET,
                HmacSha256.canonical(CLIENT, "POST", now, nonce, "a".getBytes(StandardCharsets.UTF_8))));
        AuthResult result = authenticator.authenticate(new AuthRequest(
                CLIENT, "POST", headers, "b".getBytes(StandardCharsets.UTF_8)));
        assertFalse("请求体被篡改应导致签名不匹配", result.isAuthenticated());
    }

    @Test
    public void exponentialLockoutAfterRepeatedFailures() {
        long now = System.currentTimeMillis();
        // 第 1 次失败：未锁定
        auth("bad", "POST", now, HmacSha256.generateNonce(), new byte[0], SECRET);
        // 第 2 次失败（达到阈值）：触发 50ms 锁定
        auth("bad", "POST", now, HmacSha256.generateNonce(), new byte[0], SECRET);
        AuthResult locked = authenticator.authenticate(
                new AuthRequest(CLIENT, "POST", new LinkedHashMap<String, String>(), new byte[0]));
        assertFalse("锁定期间应被拒绝", locked.isAuthenticated());
        assertEquals(LinkoraErrorCode.RATE_LIMITED, locked.getErrorCode());

        // 锁定到期后恢复
        try {
            Thread.sleep(120L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        AuthResult afterLock = auth(TOKEN, "POST", System.currentTimeMillis(),
                HmacSha256.generateNonce(), "p".getBytes(StandardCharsets.UTF_8), SECRET);
        assertTrue("锁定到期后合法请求应通过", afterLock.isAuthenticated());
    }

    @Test
    public void successResetsFailureCounter() {
        long now = System.currentTimeMillis();
        auth("bad", "POST", now, HmacSha256.generateNonce(), new byte[0], SECRET); // 1 次失败
        auth(TOKEN, "POST", now, HmacSha256.generateNonce(), "p".getBytes(StandardCharsets.UTF_8), SECRET);
        auth("bad", "POST", now, HmacSha256.generateNonce(), new byte[0], SECRET); // 又 1 次失败（计数已清零）
        // 未达到阈值 2 → 不应锁定
        AuthResult result = auth(TOKEN, "POST", now + 1,
                HmacSha256.generateNonce(), "p".getBytes(StandardCharsets.UTF_8), SECRET);
        assertTrue(result.isAuthenticated());
    }

    /** 内存凭据库（测试用）。 */
    static final class MapCredentialStore implements CredentialStore {
        private final ConcurrentHashMap<String, CredentialRecord> records =
                new ConcurrentHashMap<String, CredentialRecord>();

        void put(String clientId, String token, byte[] secret) {
            records.put(clientId, new CredentialRecord(clientId, token, secret));
        }

        @Override
        public Optional<CredentialRecord> findByClientId(String clientId) {
            return Optional.ofNullable(records.get(clientId));
        }
    }
}
