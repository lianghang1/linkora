package com.linkora.testkit;

import com.linkora.server.auth.CredentialStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用凭据库（testkit）：内存保存 clientId → token/secret 映射，
 * 供 HMAC 认证相关测试快速搭建服务端。宿主生产环境请自行实现
 * {@link CredentialStore}（落库 + 加密），不要使用本类。
 */
public final class TestCredentialStore implements CredentialStore {

    private final ConcurrentHashMap<String, CredentialStore.CredentialRecord> records =
            new ConcurrentHashMap<String, CredentialStore.CredentialRecord>();

    /** 登记一组凭据（token 与 secret 来自 {@link com.linkora.core.security.HmacSha256} 生成约定）。 */
    public TestCredentialStore add(String clientId, String token, byte[] secret) {
        records.put(clientId, new CredentialStore.CredentialRecord(clientId, token, secret));
        return this;
    }

    /** 移除凭据（测试轮换/撤销场景）。 */
    public void remove(String clientId) {
        records.remove(clientId);
    }

    @Override
    public Optional<CredentialStore.CredentialRecord> findByClientId(String clientId) {
        return Optional.ofNullable(records.get(clientId));
    }
}
