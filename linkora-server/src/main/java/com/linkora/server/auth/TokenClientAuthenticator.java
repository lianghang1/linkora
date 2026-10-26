package com.linkora.server.auth;

import com.linkora.core.error.LinkoraErrorCode;

import java.util.Map;

/**
 * Bearer Token 认证实现（0.6.0）：从 {@code Authorization: Bearer <token>}
 * （兼容 {@code X-Linkora-Token} 头）取出 token，委托宿主 {@link TokenValidator}
 * 按既有账号体系校验。
 *
 * <p>用法：宿主实现 TokenValidator 并注册为 Bean，配置
 * {@code linkora.server.auth-mode=token}；未配置校验器时启动即失败。</p>
 *
 * <p>安全说明：本实现不做失败锁定（锁定策略由宿主账号体系承担——重复 401
 * 由宿主登录接口的防护兜底）；token 走 HTTPS 传输。</p>
 *
 * @author lianghang
 */
public final class TokenClientAuthenticator implements ClientAuthenticator {

    /** 兼容的 token 头（Authorization: Bearer 优先）。 */
    public static final String AUTHORIZATION = "Authorization";
    public static final String X_LINKORA_TOKEN = "X-Linkora-Token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenValidator validator;

    public TokenClientAuthenticator(TokenValidator validator) {
        if (validator == null) {
            throw new IllegalArgumentException("TokenValidator 不能为 null");
        }
        this.validator = validator;
    }

    @Override
    public AuthResult authenticate(AuthRequest request) {
        String clientId = request.getClientId();
        if (clientId == null || clientId.trim().isEmpty()) {
            return AuthResult.failure(LinkoraErrorCode.AUTH_REQUIRED, "缺少客户端身份");
        }
        String token = extractToken(request.getHeaders());
        if (token == null || token.trim().isEmpty()) {
            return AuthResult.failure(LinkoraErrorCode.AUTH_REQUIRED,
                    "缺少 Bearer Token（Authorization: Bearer <token>）");
        }
        try {
            if (validator.validate(clientId, token)) {
                return AuthResult.success();
            }
            return AuthResult.failure(LinkoraErrorCode.AUTH_FAILED, "token 校验未通过");
        } catch (RuntimeException e) {
            // 宿主校验器异常按认证失败处理，不放大为 500
            return AuthResult.failure(LinkoraErrorCode.AUTH_FAILED,
                    "token 校验异常: " + e.getMessage());
        }
    }

    /** 从请求头提取 token；Authorization: Bearer 优先，X-Linkora-Token 兼容。 */
    static String extractToken(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (AUTHORIZATION.equalsIgnoreCase(e.getKey())
                    && e.getValue() != null
                    && e.getValue().startsWith(BEARER_PREFIX)) {
                return e.getValue().substring(BEARER_PREFIX.length()).trim();
            }
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (X_LINKORA_TOKEN.equalsIgnoreCase(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
