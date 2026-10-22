package com.linkora.spring;

import com.linkora.client.LinkoraClient;
import com.linkora.core.storage.client.ClientStore;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * LinkoraClient 客户端装配（纯 Spring 宿主，与 Boot starter 的客户端自动配置
 * 共用 {@link LinkoraClientAssembler}）。
 *
 * <p>默认关闭：宿主显式 {@code @Import(LinkoraClientConfiguration.class)} 才生效，
 * 避免纯服务端宿主意外初始化客户端。配置键与 Boot 版完全对齐（{@code linkora.client.*}）：
 * server-url 与 client-id 必填，缺失时启动即失败；db-path 未配置且宿主未提供
 * 自定义 {@link ClientStore} bean 时缺省使用 SQLite。数值型调优项
 * （sender-interval-ms / recovery-interval-ms / idle-timeout-ms）未设置时沿用 SDK 默认值。</p>
 *
 * <p>宿主自定义 {@link ClientStore}（如接业务库接管事务，见 docs/storage-spi.md）时，
 * 为自定义 bean 添加 {@code @Primary} 以避免与本装配的缺省 SQLite bean 冲突
 * （Boot 宿主由 @ConditionalOnMissingBean 自动让位，纯 Spring 无此条件机制）。</p>
 *
 * @author lianghang
 */
@Configuration
public class LinkoraClientConfiguration {

    /**
     * 缺省提供基于 SQLite 的本地队列存储；宿主自定义 {@link ClientStore} bean
     * 时请为本装配 bean 声明 {@code @Primary} 让位，否则按类型注入将出现多候选。
     */
    @Bean(destroyMethod = "close")
    public ClientStore linkoraClientStore(Environment environment) {
        return LinkoraClientAssembler.sqliteStore(environment.getProperty("linkora.client.db-path"));
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
                environment.getProperty("linkora.client.idle-timeout-ms", Long.class));
    }
}
