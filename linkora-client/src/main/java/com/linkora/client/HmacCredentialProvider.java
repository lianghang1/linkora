package com.linkora.client;

import com.linkora.core.security.HmacSha256;
import com.linkora.core.security.SecurityHeaders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * HMAC 签名凭据提供者（应用层安全，无需证书/mTLS）。
 *
 * <p>为每个出站请求附加：访问令牌、毫秒时间戳、一次性随机数、
 * HMAC-SHA256 请求签名（算法见 {@link HmacSha256#canonical}）。
 * 线程安全；时间源可注入（测试用）。</p>
 *
 * @author lianghang
 */
public final class HmacCredentialProvider implements CredentialProvider {

    private final String clientId;
    private final String accessToken;
    private final byte[] secret;
    private final LongSupplier clock;

    public HmacCredentialProvider(String clientId, String accessToken, byte[] secret) {
        this(clientId, accessToken, secret, System::currentTimeMillis);
    }

    public HmacCredentialProvider(String clientId, String accessToken, byte[] secret,
                                  LongSupplier clock) {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        if (accessToken == null || accessToken.trim().isEmpty()) {
            throw new IllegalArgumentException("accessToken 不能为空");
        }
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret 不能为空");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为 null");
        }
        this.clientId = clientId;
        this.accessToken = accessToken;
        this.secret = secret.clone();
        this.clock = clock;
    }

    @Override
    public Map<String, String> authHeaders(RequestMaterial material) {
        long timestamp = clock.getAsLong();
        String nonce = HmacSha256.generateNonce();
        String signature = HmacSha256.sign(secret,
                HmacSha256.canonical(clientId, material.getMethod(), timestamp, nonce,
                        material.getBody()));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put(SecurityHeaders.ACCESS_TOKEN, accessToken);
        headers.put(SecurityHeaders.TIMESTAMP, String.valueOf(timestamp));
        headers.put(SecurityHeaders.NONCE, nonce);
        headers.put(SecurityHeaders.SIGNATURE, signature);
        return headers;
    }

    /** 清零密钥（close 语义支持，尽力而为）。 */
    public void destroySecret() {
        for (int i = 0; i < secret.length; i++) {
            secret[i] = 0;
        }
    }
}
