package com.linkora.spring;

import com.linkora.client.LinkoraClient;
import com.linkora.client.UplinkPublisher;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.jdbc.JdbcClientStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;

/**
 * LinkoraClient 客户端装配（纯 Spring 宿主，与 Boot starter 的客户端自动配置
 * 共用 {@link LinkoraClientAssembler}）。
 *
 * <p>默认关闭：宿主显式 {@code @Import(LinkoraClientConfiguration.class)} 才生效，
 * 避免纯服务端宿主意外初始化客户端。配置键与 Boot 版完全对齐（{@code linkora.client.*}）：
 * server-url 与 client-id 必填，缺失时启动即失败。存储模式与 Boot 版对齐（0.5.0）：
 * {@code linkora.client.store=sqlite}（默认，本地文件，离线缓冲优先）或
 * {@code datasource}（复用宿主 DataSource，事务感知包装后交给
 * {@link JdbcClientStore}，业务消息同库同事务）。数值型调优项
 * （sender-interval-ms / recovery-interval-ms / idle-timeout-ms）未设置时沿用 SDK 默认值。</p>
 *
 * <p>宿主自定义 {@link ClientStore} 时，
 * 为自定义 bean 添加 {@code @Primary} 以避免与本装配的缺省存储 bean 冲突
 * （Boot 宿主由 @ConditionalOnMissingBean 自动让位，纯 Spring 无此条件机制）。</p>
 *
 * @author lianghang
 */
@Configuration
public class LinkoraClientConfiguration {

    /**
     * 按 {@code linkora.client.store} 提供本地队列存储；宿主自定义 {@link ClientStore}
     * bean 时请为本装配 bean 声明 {@code @Primary} 让位，否则按类型注入将出现多候选。
     */
    @Bean(destroyMethod = "close")
    public ClientStore linkoraClientStore(Environment environment,
                                          ObjectProvider<DataSource> hostDataSource) {
        String store = environment.getProperty("linkora.client.store", "sqlite");
        if ("datasource".equalsIgnoreCase(store.trim())) {
            DataSource dataSource = hostDataSource.getIfAvailable();
            if (dataSource == null) {
                throw new IllegalStateException(
                        "linkora.client.store=datasource 需要宿主提供 DataSource Bean"
                                + "（或改用 linkora.client.store=sqlite）");
            }
            return new JdbcClientStore(TxAwareDataSources.wrap(dataSource));
        }
        return LinkoraClientAssembler.sqliteStore(
                environment.getProperty("linkora.client.db-path",
                        "./linkora/linkora-client.db"));
    }

    /**
     * 组装并托管 {@link LinkoraClient} 生命周期（start/close 由容器管理）。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public LinkoraClient linkoraClient(ClientStore clientStore, Environment environment) {
        return LinkoraClientAssembler.build(
                environment.getProperty("linkora.client.server-url"),
                environment.getProperty("linkora.client.client-id"),
                clientStore,
                environment.getProperty("linkora.client.sender-interval-ms", Long.class),
                environment.getProperty("linkora.client.recovery-interval-ms", Long.class),
                environment.getProperty("linkora.client.idle-timeout-ms", Long.class),
                cipherKey(environment),
                credentialProvider(environment));
    }

    /**
     * 上行发送便捷入口（发布 + 等待服务端确认），随 {@link LinkoraClient} 一起提供。
     */
    @Bean
    public UplinkPublisher linkoraUplinkPublisher(LinkoraClient linkoraClient,
                                                  ClientStore clientStore) {
        return new UplinkPublisher(linkoraClient, clientStore);
    }

    /**
     * 传输加密密钥解析（0.6.0 默认开启）：linkora.payload-cipher 默认 true，
     * 开启时 linkora.payload-cipher-key 必配，缺失启动即失败；与认证方式正交。
     */
    static String cipherKey(Environment environment) {
        boolean enabled = environment.getProperty("linkora.payload-cipher",
                Boolean.class, Boolean.TRUE);
        String key = environment.getProperty("linkora.payload-cipher-key");
        if (enabled && (key == null || key.trim().isEmpty())) {
            throw new IllegalStateException(
                    "linkora.payload-cipher 默认开启，必须配置 linkora.payload-cipher-key"
                            + "（openssl rand -base64 32 生成，两端一致）；"
                            + "如确不需要传输加密请设置 linkora.payload-cipher=false");
        }
        return enabled ? key : null;
    }

    /**
     * 客户端凭据解析（0.6.0，与 starter 同语义）：hmac（默认）| token（静态
     * auth-token 或 TokenProvider Bean 由宿主注入后以
     * {@code TokenCredentialProvider.forProvider} 传入）| none（匿名）。
     * 纯 Spring 宿主如需动态 TokenProvider，请直接定义
     * {@code LinkoraClient} Bean 或用 Builder 装配。
     */
    static com.linkora.client.CredentialProvider credentialProvider(
            org.springframework.core.env.Environment environment) {
        String mode = environment.getProperty("linkora.client.auth-mode", "hmac").trim().toLowerCase();
        if ("token".equals(mode)) {
            String staticToken = environment.getProperty("linkora.client.auth-token");
            if (staticToken == null || staticToken.trim().isEmpty()) {
                throw new IllegalStateException(
                        "linkora.client.auth-mode=token 需要 linkora.client.auth-token 静态配置"
                                + "（动态 TokenProvider 场景请用 Builder 自行装配）");
            }
            return com.linkora.client.auth.TokenCredentialProvider.forStaticToken(staticToken);
        }
        return null;
    }
}
