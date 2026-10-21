package com.linkora.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * linkora.client.* 配置属性。未设置的数值项使用 Linkora SDK 默认值（见 ClientConfig）。
 *
 * @author lianghang
 */
@ConfigurationProperties(prefix = "linkora.client")
public class LinkoraClientProperties {

    /** 是否启用客户端自动装配（默认关闭：多数服务端宿主不需要客户端角色）。 */
    private boolean enabled = false;

    /** 服务端接入根地址（必填）。 */
    private String serverUrl;
    /** 本端客户端标识（必填）。 */
    private String clientId;
    /** SQLite 本地队列文件路径；未设置时要求宿主自行提供 ClientStore bean。 */
    private String dbPath;

    private Long senderIntervalMs;
    private Long recoveryIntervalMs;
    private Long idleTimeoutMs;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getDbPath() {
        return dbPath;
    }

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
    }

    public Long getSenderIntervalMs() {
        return senderIntervalMs;
    }

    public void setSenderIntervalMs(Long senderIntervalMs) {
        this.senderIntervalMs = senderIntervalMs;
    }

    public Long getRecoveryIntervalMs() {
        return recoveryIntervalMs;
    }

    public void setRecoveryIntervalMs(Long recoveryIntervalMs) {
        this.recoveryIntervalMs = recoveryIntervalMs;
    }

    public Long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public void setIdleTimeoutMs(Long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }
}
