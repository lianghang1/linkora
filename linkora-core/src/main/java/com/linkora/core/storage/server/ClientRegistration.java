package com.linkora.core.storage.server;

/**
 * 已注册客户端的身份信息。凭据材料（token/密钥）由认证模块管理，
 * 不在本对象中保存明文凭据。
 */
public final class ClientRegistration {

    private final String clientId;
    private final boolean enabled;
    private final long createdAt;

    public ClientRegistration(String clientId, boolean enabled, long createdAt) {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        this.clientId = clientId;
        this.enabled = enabled;
        this.createdAt = createdAt;
    }

    public String getClientId() {
        return clientId;
    }

    /** 禁用的客户端拒绝建连与上报。 */
    public boolean isEnabled() {
        return enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
