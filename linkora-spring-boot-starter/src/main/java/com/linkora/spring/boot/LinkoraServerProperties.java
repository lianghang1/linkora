package com.linkora.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * linkora.server.* 配置属性。未设置的数值项使用 Linkora SDK 默认值（见 ServerConfig）。
 *
 * @author lianghang
 */
@ConfigurationProperties(prefix = "linkora.server")
public class LinkoraServerProperties {

    /** 是否启用服务端自动装配。 */
    private boolean enabled = true;

    /** 启动时是否对宿主 DataSource 执行 lk_* schema 迁移。 */
    private boolean autoMigrate = true;

    private String eventsPath = "/events";
    private String messagesPath = "/messages";
    private String acksPath = "/acks";

    /** SSE 心跳间隔（毫秒），0 表示禁用。 */
    private Long heartbeatIntervalMs = 15_000L;
    /** SSE retry 重连建议间隔（毫秒）。 */
    private Long reconnectHintMs = 3_000L;

    /** 启动时自动注册的客户端（clientId → 是否启用）。 */
    private Map<String, Boolean> registerClients = new LinkedHashMap<String, Boolean>();

    // ---- ServerConfig 可调项（null = SDK 默认） ----
    private Long sweepIntervalMs;
    private Integer pushBatchSize;
    private Integer maxDeliveryRetries;
    private Long retryBackoffBaseMs;
    private Long retryBackoffMaxMs;
    private Long pushPacingBaseMs;
    private Long pushPacingMaxMs;
    private Long messageSizeLimitBytes;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoMigrate() {
        return autoMigrate;
    }

    public void setAutoMigrate(boolean autoMigrate) {
        this.autoMigrate = autoMigrate;
    }

    public String getEventsPath() {
        return eventsPath;
    }

    public void setEventsPath(String eventsPath) {
        this.eventsPath = eventsPath;
    }

    public String getMessagesPath() {
        return messagesPath;
    }

    public void setMessagesPath(String messagesPath) {
        this.messagesPath = messagesPath;
    }

    public String getAcksPath() {
        return acksPath;
    }

    public void setAcksPath(String acksPath) {
        this.acksPath = acksPath;
    }

    public Long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(Long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public Long getReconnectHintMs() {
        return reconnectHintMs;
    }

    public void setReconnectHintMs(Long reconnectHintMs) {
        this.reconnectHintMs = reconnectHintMs;
    }

    public Map<String, Boolean> getRegisterClients() {
        return registerClients;
    }

    public void setRegisterClients(Map<String, Boolean> registerClients) {
        this.registerClients = registerClients;
    }

    public Long getSweepIntervalMs() {
        return sweepIntervalMs;
    }

    public void setSweepIntervalMs(Long sweepIntervalMs) {
        this.sweepIntervalMs = sweepIntervalMs;
    }

    public Integer getPushBatchSize() {
        return pushBatchSize;
    }

    public void setPushBatchSize(Integer pushBatchSize) {
        this.pushBatchSize = pushBatchSize;
    }

    public Integer getMaxDeliveryRetries() {
        return maxDeliveryRetries;
    }

    public void setMaxDeliveryRetries(Integer maxDeliveryRetries) {
        this.maxDeliveryRetries = maxDeliveryRetries;
    }

    public Long getRetryBackoffBaseMs() {
        return retryBackoffBaseMs;
    }

    public void setRetryBackoffBaseMs(Long retryBackoffBaseMs) {
        this.retryBackoffBaseMs = retryBackoffBaseMs;
    }

    public Long getRetryBackoffMaxMs() {
        return retryBackoffMaxMs;
    }

    public void setRetryBackoffMaxMs(Long retryBackoffMaxMs) {
        this.retryBackoffMaxMs = retryBackoffMaxMs;
    }

    public Long getPushPacingBaseMs() {
        return pushPacingBaseMs;
    }

    public void setPushPacingBaseMs(Long pushPacingBaseMs) {
        this.pushPacingBaseMs = pushPacingBaseMs;
    }

    public Long getPushPacingMaxMs() {
        return pushPacingMaxMs;
    }

    public void setPushPacingMaxMs(Long pushPacingMaxMs) {
        this.pushPacingMaxMs = pushPacingMaxMs;
    }

    public Long getMessageSizeLimitBytes() {
        return messageSizeLimitBytes;
    }

    public void setMessageSizeLimitBytes(Long messageSizeLimitBytes) {
        this.messageSizeLimitBytes = messageSizeLimitBytes;
    }
}
