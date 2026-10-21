package com.linkora.spring.boot;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.storage.client.ClientStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;

/**
 * 客户端自动装配（默认关闭，linkora.client.enabled=true 开启）。
 *
 * <p>未配置 {@code linkora.client.db-path} 时默认使用 SQLite 本地队列；
 * 宿主定义自己的 {@link ClientStore} bean（如接 H2/MySQL 业务库接管事务，
 * 见 docs/storage-spi.md）即可整体覆盖。</p>
 *
 * @author lianghang
 */
@Configuration
@ConditionalOnClass(name = {
        "com.linkora.client.LinkoraClient",
        "com.linkora.client.storage.sqlite.SqliteClientStore"})
@ConditionalOnProperty(prefix = "linkora.client", name = "enabled",
        havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(LinkoraClientProperties.class)
public class LinkoraClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LinkoraClientAutoConfiguration.class);

    /**
     * 缺省提供基于 SQLite 的本地队列存储；宿主已定义 {@link ClientStore} bean 时本方法不生效。
     *
     * @param properties 客户端配置属性
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ClientStore.class)
    public ClientStore linkoraClientStore(LinkoraClientProperties properties) {
        if (properties.getDbPath() == null || properties.getDbPath().trim().isEmpty()) {
            throw new IllegalStateException(
                    "启用 linkora.client 后必须配置 linkora.client.db-path，"
                            + "或由宿主提供自定义 ClientStore bean");
        }
        log.info("客户端本地队列使用 SQLite: {}", properties.getDbPath());
        return new SqliteClientStore(Paths.get(properties.getDbPath().trim()));
    }

    /**
     * 组装 {@link LinkoraClient}；server-url 与 client-id 必填，缺失时启动即失败，
     * 数值型调优项未设置时沿用 SDK 默认值。
     *
     * @param clientStore 消息本地存储
     * @param properties 客户端配置属性
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public LinkoraClient linkoraClient(ClientStore clientStore,
                                       LinkoraClientProperties properties) {
        if (properties.getServerUrl() == null || properties.getServerUrl().trim().isEmpty()) {
            throw new IllegalStateException("启用 linkora.client 后必须配置 linkora.client.server-url");
        }
        if (properties.getClientId() == null || properties.getClientId().trim().isEmpty()) {
            throw new IllegalStateException("启用 linkora.client 后必须配置 linkora.client.client-id");
        }
        ClientConfig.Builder config = ClientConfig.builder();
        if (properties.getSenderIntervalMs() != null) {
            config.senderIntervalMs(properties.getSenderIntervalMs());
        }
        if (properties.getRecoveryIntervalMs() != null) {
            config.recoveryIntervalMs(properties.getRecoveryIntervalMs());
        }
        if (properties.getIdleTimeoutMs() != null) {
            config.idleTimeoutMs(properties.getIdleTimeoutMs());
        }
        return LinkoraClient.builder()
                .serverUrl(properties.getServerUrl().trim())
                .clientId(properties.getClientId().trim())
                .clientStore(clientStore)
                .config(config.build())
                .build();
    }
}
