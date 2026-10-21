package com.linkora.server.auth;

import java.util.Optional;

/**
 * 客户端凭据存储 SPI：服务端按 clientId 查找已发放的访问令牌与 HMAC 密钥。
 * 生产实现由宿主提供（通常落在认证库表），测试可使用内存实现。
 *
 * @author lianghang
 */
public interface CredentialStore {

    Optional<CredentialRecord> findByClientId(String clientId);

    /** 已发放的客户端凭据（secret 只在内存中短暂持有）。 */
    final class CredentialRecord {

        private final String clientId;
        private final String accessToken;
        private final byte[] secret;

        public CredentialRecord(String clientId, String accessToken, byte[] secret) {
            if (clientId == null || clientId.trim().isEmpty()
                    || accessToken == null || accessToken.trim().isEmpty()
                    || secret == null || secret.length == 0) {
                throw new IllegalArgumentException("凭据字段不完整");
            }
            this.clientId = clientId;
            this.accessToken = accessToken;
            this.secret = secret.clone();
        }

        public String getClientId() {
            return clientId;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public byte[] getSecret() {
            return secret.clone();
        }
    }
}
