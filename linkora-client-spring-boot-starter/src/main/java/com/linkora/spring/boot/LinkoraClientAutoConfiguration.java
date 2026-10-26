package com.linkora.spring.boot;

import com.linkora.client.LinkoraClient;
import com.linkora.client.UplinkPublisher;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.jdbc.JdbcClientStore;
import com.linkora.spring.LinkoraClientAssembler;
import com.linkora.spring.TxAwareDataSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 客户端自动装配（默认关闭，linkora.client.enabled=true 开启）。
 *
 * <p>存储模式与服务端对等（0.5.0）：{@code linkora.client.store=sqlite}
 * （默认，本地文件，离线缓冲优先）或 {@code datasource}（复用宿主 DataSource，
 * 业务消息同库同事务）。宿主自定义 {@link ClientStore} Bean 时整体接管。</p>
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
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LinkoraClientProperties.class)
public class LinkoraClientAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LinkoraClientAutoConfiguration.class);

    /**
     * 缺省按 {@code linkora.client.store} 提供本地队列存储；
     * 宿主已定义 {@link ClientStore} bean 时本方法不生效。
     *
     * <p>datasource 模式：宿主 DataSource 以事务感知代理包装后交给
     * {@link JdbcClientStore}——宿主 {@code @Transactional} 事务内的 publish
     * 随业务一起提交/回滚（transactional outbox）。</p>
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ClientStore.class)
    public ClientStore linkoraClientStore(LinkoraClientProperties properties,
                                          ObjectProvider<DataSource> hostDataSource) {
        String store = properties.getStore() == null
                ? "sqlite" : properties.getStore().trim().toLowerCase();
        if ("datasource".equals(store)) {
            DataSource dataSource = hostDataSource.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException(
                        "linkora.client.store=datasource 需要宿主提供 DataSource Bean"
                                + "（或改用 linkora.client.store=sqlite）");
            }
            log.info("Linkora 客户端存储模式: datasource（与宿主业务同库同事务）");
            return new JdbcClientStore(TxAwareDataSources.wrap(dataSource));
        }
        if (!"sqlite".equals(store)) {
            throw new IllegalStateException(
                    "linkora.client.store 仅支持 sqlite | datasource，当前: " + store);
        }
        log.info("Linkora 客户端存储模式: sqlite（{}）",
                properties.getDbPath() == null ? "默认路径" : properties.getDbPath());
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
                                       LinkoraClientProperties properties,
                                       org.springframework.core.env.Environment environment,
                                       org.springframework.beans.factory.ObjectProvider<com.linkora.client.auth.TokenProvider> tokenProviderProvider) {
        // 根级配置 linkora.payload-cipher[-key]（与认证解耦的共享键位，两端一致）
        boolean cipher = environment.getProperty("linkora.payload-cipher",
                Boolean.class, Boolean.TRUE);
        String key = environment.getProperty("linkora.payload-cipher-key");
        if (cipher && (key == null || key.trim().isEmpty())) {
            throw new IllegalStateException(
                    "linkora.payload-cipher 默认开启，必须配置 linkora.payload-cipher-key"
                            + "（openssl rand -base64 32 生成，两端一致）；"
                            + "如确不需要传输加密请设置 linkora.payload-cipher=false");
        }
        return LinkoraClientAssembler.build(properties.getServerUrl(),
                properties.getClientId(), clientStore,
                properties.getSenderIntervalMs(),
                properties.getRecoveryIntervalMs(),
                properties.getIdleTimeoutMs(),
                cipher ? key : null,
                resolveCredential(environment, tokenProviderProvider));
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

    /**
     * 客户端凭据解析（0.6.0）：linkora.client.auth-mode = hmac（默认，凭证走
     * ClientStore 凭证体系）| token（Bearer Token，宿主账号体系下发——
     * TokenProvider Bean 优先，其次静态 auth-token）| none（匿名）。
     */
    static com.linkora.client.CredentialProvider resolveCredential(
            org.springframework.core.env.Environment environment,
            org.springframework.beans.factory.ObjectProvider<com.linkora.client.auth.TokenProvider> tokenProviderProvider) {
        String mode = environment.getProperty("linkora.client.auth-mode", "hmac").trim().toLowerCase();
        if ("token".equals(mode)) {
            com.linkora.client.auth.TokenProvider dynamic = tokenProviderProvider.getIfAvailable();
            if (dynamic != null) {
                return com.linkora.client.auth.TokenCredentialProvider.forProvider(dynamic);
            }
            String staticToken = environment.getProperty("linkora.client.auth-token");
            if (staticToken == null || staticToken.trim().isEmpty()) {
                throw new IllegalStateException(
                        "linkora.client.auth-mode=token 需要 TokenProvider Bean 或"
                                + " linkora.client.auth-token 静态配置");
            }
            return com.linkora.client.auth.TokenCredentialProvider.forStaticToken(staticToken);
        }
        if ("none".equals(mode)) {
            return null; // Builder 默认即 NoCredentialProvider（匿名）
        }
        return null; // hmac：默认凭证体系
    }
}
