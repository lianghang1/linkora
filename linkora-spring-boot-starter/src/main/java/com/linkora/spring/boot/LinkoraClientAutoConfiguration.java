package com.linkora.spring.boot;

import com.linkora.client.LinkoraClient;
import com.linkora.client.UplinkPublisher;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.spring.LinkoraClientAssembler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端自动装配（默认关闭，linkora.client.enabled=true 开启）。
 *
 * <p>组装逻辑单点在 {@link LinkoraClientAssembler}（与纯 Spring 宿主共用），
 * 本类只做 Boot 特有的条件化与属性绑定。</p>
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

    /**
     * 缺省提供基于 SQLite 的本地队列存储；宿主已定义 {@link ClientStore} bean 时本方法不生效。
     *
     * @param properties 客户端配置属性
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ClientStore.class)
    public ClientStore linkoraClientStore(LinkoraClientProperties properties) {
        return LinkoraClientAssembler.sqliteStore(properties.getDbPath());
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
        return LinkoraClientAssembler.build(properties.getServerUrl(),
                properties.getClientId(), clientStore,
                properties.getSenderIntervalMs(),
                properties.getRecoveryIntervalMs(),
                properties.getIdleTimeoutMs());
    }

    /**
     * 上行发送便捷入口（发布 + 等待服务端确认）；宿主已定义 UplinkPublisher bean 时不生效。
     */
    @Bean
    @ConditionalOnMissingBean(UplinkPublisher.class)
    public UplinkPublisher linkoraUplinkPublisher(LinkoraClient linkoraClient,
                                                  ClientStore clientStore) {
        return new UplinkPublisher(linkoraClient, clientStore);
    }
}
