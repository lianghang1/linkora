package com.linkora.server.auth;

/**
 * 放行所有请求的认证器 —— 仅用于本地开发与测试，禁止在生产环境使用。
 * 它不校验任何凭据，但保留了"clientId 必须存在"的最低约束。
 *
 * @author lianghang
 */
public final class AllowAllClientAuthenticator implements ClientAuthenticator {

    private final boolean requireClientId;

    public AllowAllClientAuthenticator() {
        this(true);
    }

    public AllowAllClientAuthenticator(boolean requireClientId) {
        this.requireClientId = requireClientId;
    }

    @Override
    public AuthResult authenticate(AuthRequest request) {
        if (requireClientId
                && (request.getClientId() == null || request.getClientId().trim().isEmpty())) {
            return AuthResult.failure(com.linkora.core.error.LinkoraErrorCode.AUTH_REQUIRED,
                    "缺少客户端身份（X-Linkora-Client-Id）");
        }
        return AuthResult.success();
    }
}
