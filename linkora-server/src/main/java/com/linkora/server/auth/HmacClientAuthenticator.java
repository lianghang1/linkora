package com.linkora.server.auth;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.security.HmacSha256;
import com.linkora.core.security.SecurityHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 应用层认证器：高熵访问令牌 + HMAC-SHA256 请求签名 + 时间戳窗口
 * + Nonce 防重放 + 失败指数锁定。无需证书/PKI 基础设施。
 *
 * <p>校验顺序（失败均计入失败锁定，成功清零）：</p>
 * <ol>
 *   <li>clientId 存在且未被锁定；</li>
 *   <li>凭据记录存在、令牌恒定时间匹配；</li>
 *   <li>时间戳在允许窗口内（默认 ±5 分钟）；</li>
 *   <li>Nonce 未出现过（防重放）；</li>
 *   <li>HMAC 签名恒定时间匹配。</li>
 * </ol>
 */
public final class HmacClientAuthenticator implements ClientAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(HmacClientAuthenticator.class);

    private final CredentialStore credentialStore;
    private final long maxClockSkewMs;
    private final FailureLockout lockout;
    private final NonceCache nonceCache;

    public HmacClientAuthenticator(CredentialStore credentialStore) {
        this(credentialStore, 5 * 60 * 1000L,
                new FailureLockout(5, 2_000L, 15 * 60 * 1000L),
                new NonceCache(10 * 60 * 1000L, 100_000));
    }

    public HmacClientAuthenticator(CredentialStore credentialStore, long maxClockSkewMs,
                                   FailureLockout lockout, NonceCache nonceCache) {
        if (credentialStore == null || lockout == null || nonceCache == null) {
            throw new IllegalArgumentException("认证器依赖不能为 null");
        }
        if (maxClockSkewMs < 1) {
            throw new IllegalArgumentException("maxClockSkewMs 必须为正数");
        }
        this.credentialStore = credentialStore;
        this.maxClockSkewMs = maxClockSkewMs;
        this.lockout = lockout;
        this.nonceCache = nonceCache;
    }

    @Override
    public AuthResult authenticate(AuthRequest request) {
        String clientId = request.getClientId();
        if (clientId == null || clientId.trim().isEmpty()) {
            return AuthResult.failure(LinkoraErrorCode.AUTH_REQUIRED, "缺少客户端身份");
        }
        long now = System.currentTimeMillis();
        if (lockout.isLocked(clientId, now)) {
            log.warn("客户端处于认证锁定期: clientId={}", clientId);
            return AuthResult.failure(LinkoraErrorCode.RATE_LIMITED, "认证失败次数过多，请稍后重试");
        }
        Optional<CredentialStore.CredentialRecord> recordOpt = credentialStore.findByClientId(clientId);
        if (!recordOpt.isPresent()) {
            recordFailure(clientId, now);
            return AuthResult.failure(LinkoraErrorCode.AUTH_FAILED, "未知客户端");
        }
        CredentialStore.CredentialRecord record = recordOpt.get();

        String token = request.header(SecurityHeaders.ACCESS_TOKEN);
        if (token == null || !HmacSha256.constantTimeEquals(record.getAccessToken(), token.trim())) {
            return failure(clientId, now, "令牌无效");
        }

        String timestampText = request.header(SecurityHeaders.TIMESTAMP);
        String nonce = request.header(SecurityHeaders.NONCE);
        String signature = request.header(SecurityHeaders.SIGNATURE);
        if (timestampText == null || nonce == null || signature == null) {
            return failure(clientId, now, "缺少签名头");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampText.trim());
        } catch (NumberFormatException e) {
            return failure(clientId, now, "时间戳格式非法");
        }
        if (Math.abs(now - timestamp) > maxClockSkewMs) {
            return failure(clientId, now, "时间戳超出允许窗口");
        }
        if (nonceCache.seenOrRecord(nonce.trim(), now)) {
            return failure(clientId, now, "nonce 重复（疑似重放）");
        }
        String canonical = HmacSha256.canonical(clientId, request.getMethod(), timestamp,
                nonce.trim(), request.getBody());
        String expected = HmacSha256.sign(record.getSecret(), canonical);
        if (!HmacSha256.constantTimeEquals(expected, signature.trim())) {
            return failure(clientId, now, "签名不匹配");
        }
        lockout.recordSuccess(clientId);
        return AuthResult.success();
    }

    private AuthResult failure(String clientId, long now, String reason) {
        recordFailure(clientId, now);
        log.warn("认证失败: clientId={}, 原因: {}", clientId, reason);
        return AuthResult.failure(LinkoraErrorCode.AUTH_FAILED, "认证失败");
    }

    private void recordFailure(String clientId, long now) {
        Long lockedUntil = lockout.recordFailure(clientId, now);
        if (lockedUntil != null) {
            log.warn("客户端触发认证指数锁定至 {}: clientId={}", lockedUntil, clientId);
        }
    }
}
