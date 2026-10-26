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

    /**
     * 存储模式（0.5.0 起，与客户端对等）：
     * <ul>
     *   <li>{@code sqlite}（默认）：内嵌 SQLite 文件，零配置起步，
     *       适用于开发/演示/小规模部署；</li>
     *   <li>{@code datasource}：复用宿主 DataSource（业务消息同库同事务），
     *       生产推荐。</li>
     * </ul>
     */
    private String store = "sqlite";

    /** sqlite 模式的数据库文件路径。 */
    private String dbPath = "./linkora/linkora-server.db";

    /**
     * 未提供 ClientAuthenticator 时是否允许回退为"放行全部请求"。
     *
     * <p>默认 false：缺认证器直接启动失败。仅本地开发/演示应设为 true。</p>
     */
    private boolean allowAnonymous = false;

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
    private Integer retentionDays;

    // ---- 死信告警（0.4.2 起，null/空 = 关闭告警） ----
    /** 死信/卡死告警 Webhook 地址（企业微信/钉钉/飞书/自定义机器人），不配置即关闭。 */
    private String alertWebhookUrl;

    /** payload 传输加密开关（0.6.0 默认开启，与认证方式无关）。 */
    private boolean payloadCipher = true;

    /** 传输加密密钥（Base64, 32 字节）；默认开启时必配。 */
    private String payloadCipherKey;
    /** 告警消息格式：raw | wecom | dingtalk | feishu，默认 raw。 */
    private String alertWebhookType;
    /** 收件箱"卡死"判定阈值（分钟），投递未终结且停留超过该时长视为异常，默认 30。 */
    private Integer inboxStuckMinutes;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAllowAnonymous() {
        return allowAnonymous;
    }

    public void setAllowAnonymous(boolean allowAnonymous) {
        this.allowAnonymous = allowAnonymous;
    }

    public boolean isAutoMigrate() {
        return autoMigrate;
    }

    public void setAutoMigrate(boolean autoMigrate) {
        this.autoMigrate = autoMigrate;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getDbPath() {
        return dbPath;
    }

    public void setDbPath(String dbPath) {
        this.dbPath = dbPath;
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

    public Integer getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(Integer retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getAlertWebhookUrl() {
        return alertWebhookUrl;
    }

    public void setAlertWebhookUrl(String alertWebhookUrl) {
        this.alertWebhookUrl = alertWebhookUrl;
    }

    public boolean isPayloadCipher() {
        return payloadCipher;
    }

    public void setPayloadCipher(boolean payloadCipher) {
        this.payloadCipher = payloadCipher;
    }

    public String getPayloadCipherKey() {
        return payloadCipherKey;
    }

    public void setPayloadCipherKey(String payloadCipherKey) {
        this.payloadCipherKey = payloadCipherKey;
    }

    public String getAlertWebhookType() {
        return alertWebhookType;
    }

    public void setAlertWebhookType(String alertWebhookType) {
        this.alertWebhookType = alertWebhookType;
    }

    public Integer getInboxStuckMinutes() {
        return inboxStuckMinutes;
    }

    public void setInboxStuckMinutes(Integer inboxStuckMinutes) {
        this.inboxStuckMinutes = inboxStuckMinutes;
    }
}
