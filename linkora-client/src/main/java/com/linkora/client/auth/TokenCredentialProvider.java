package com.linkora.client.auth;

import com.linkora.client.CredentialProvider;

import java.util.Collections;
import java.util.Map;

/**
 * Bearer Token 凭据实现（0.6.0）：为每个出站请求附加
 * {@code Authorization: Bearer <token>}（兼容 {@code X-Linkora-Token}）。
 *
 * <p>token 来源二选一：静态配置（{@link #forStaticToken}）或宿主
 * {@link TokenProvider} 动态获取（{@link #forProvider}，推荐——每次请求
 * 取最新 token，宿主可无缝续期）。</p>
 *
 * @author lianghang
 */
public final class TokenCredentialProvider implements CredentialProvider {

    private final TokenProvider provider;

    private TokenCredentialProvider(TokenProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("TokenProvider 不能为 null");
        }
        this.provider = provider;
    }

    /** 静态 token（简单场景：配置文件写死，过期需换配置）。 */
    public static TokenCredentialProvider forStaticToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("auth-token 不能为空");
        }
        final String t = token.trim();
        return new TokenCredentialProvider(new TokenProvider() {
            @Override
            public String currentToken() {
                return t;
            }
        });
    }

    /** 动态 token（推荐：每次请求回调宿主取当前 token，宿主内部可续期）。 */
    public static TokenCredentialProvider forProvider(TokenProvider provider) {
        return new TokenCredentialProvider(provider);
    }

    @Override
    public Map<String, String> authHeaders(RequestMaterial material) {
        String token = provider.currentToken();
        if (token == null || token.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.<String, String>singletonMap(
                "Authorization", "Bearer " + token.trim());
    }
}
