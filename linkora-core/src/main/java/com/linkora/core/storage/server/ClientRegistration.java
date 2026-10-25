package com.linkora.core.storage.server;

/**
 * 已注册客户端的身份信息。凭据材料（token/密钥）由认证模块管理，
 * 不在本对象中保存明文凭据。
 *
 * <p>{@code displayName} 为可选的人类可读名称（如「门店003」），供运维控制台
 * 等界面展示；null 时界面回退显示 {@code clientId}。它不参与任何协议语义。</p>
 */
public final class ClientRegistration {

    private final String clientId;
    private final boolean enabled;
    private final long createdAt;
    private final String displayName;

    public ClientRegistration(String clientId, boolean enabled, long createdAt) {
        this(clientId, enabled, createdAt, null);
    }

    public ClientRegistration(String clientId, boolean enabled, long createdAt, String displayName) {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("clientId 不能为空");
        }
        this.clientId = clientId;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? null : displayName.trim();
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

    /** 可为 null（显示时回退到 clientId）。 */
    public String getDisplayName() {
        return displayName;
    }
}
