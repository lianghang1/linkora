package com.linkora.spring.boot;

import com.linkora.core.storage.embedded.EmbeddedSqliteDatabase;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.pipeline.IngestListener;
import com.linkora.server.servlet.LinkoraEndpointServlet;
import com.linkora.server.servlet.SsePushServlet;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import com.linkora.spring.LinkoraServerAssembler;
import com.linkora.spring.TxAwareDataSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 服务端自动装配：按 {@code linkora.server.store} 选择存储模式（0.5.0 起
 * 与客户端对等）——
 *
 * <ul>
 *   <li><b>sqlite</b>（默认）：{@link EmbeddedSqliteDatabase} 内嵌文件库，
 *       零配置起步，适用于开发/演示/小规模部署；</li>
 *   <li><b>datasource</b>：复用宿主 DataSource（事务感知包装，业务消息同库
 *       同事务），生产推荐。</li>
 * </ul>
 *
 * <p>默认使用 linkora-server 内置的 JDBC 仓储；宿主定义自己的
 * Repository/Registry bean 即可整体覆盖。宿主定义自己的 {@link LinkoraServer}
 * bean 时本配置的 server bean 退位，但三端点 Servlet 仍会注册到该实例上。</p>
 *
 * <p>安全：未提供 ClientAuthenticator 时回退 AllowAll 并告警，生产环境必须
 * 提供真实认证器（见 docs/security.md）。</p>
 *
 * @author lianghang
 */
@Configuration
@ConditionalOnClass(name = {
        "com.linkora.server.LinkoraServer",
        "com.linkora.server.storage.jdbc.JdbcMessageRepository"})
@ConditionalOnProperty(prefix = "linkora.server", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(LinkoraServerProperties.class)
public class LinkoraServerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServerAutoConfiguration.class);

    /** 统一存储数据源 Bean 名：两端存储生态（仓储/服务端）都从这里取连接。 */
    public static final String SERVER_STORAGE_BEAN = "linkoraServerStorage";

    /**
     * 内嵌 SQLite 库：store=sqlite（默认）时创建并迁移 schema。
     * store=datasource 时不创建，由宿主 DataSource 承担存储。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "linkora.server", name = "store",
            havingValue = "sqlite", matchIfMissing = true)
    public EmbeddedSqliteDatabase linkoraEmbeddedServerDb(LinkoraServerProperties properties) {
        EmbeddedSqliteDatabase db = EmbeddedSqliteDatabase.atFile(
                properties.getDbPath() == null ? "./data/linkora-server.db"
                        : properties.getDbPath());
        JdbcServerSchema.migrate(db.dataSource());
        log.info("Linkora 服务端存储模式: sqlite（内嵌 {}）。"
                        + "适用于开发/演示/小规模部署，生产高并发建议 store=datasource",
                db.dbFile());
        return db;
    }

    /**
     * 服务端存储数据源：内嵌库优先（store=sqlite）；否则取宿主唯一候选
     * DataSource（store=datasource 语义）。两种模式统一以事务感知代理包装——
     * 宿主 {@code @Transactional} 事务内的 server.publish 随业务原子提交
     * （事务绑定按 DataSource 实例生效，仓储取连接必须经过同一代理）。
     * 内嵌库与宿主 DataSource 皆无时快速失败并给出修正指引。
     */
    @Bean(name = SERVER_STORAGE_BEAN)
    public DataSource linkoraServerStorage(ObjectProvider<EmbeddedSqliteDatabase> embedded,
                                           ObjectProvider<DataSource> hostDataSource,
                                           LinkoraServerProperties properties) {
        EmbeddedSqliteDatabase e = embedded.getIfAvailable();
        if (e != null) {
            return TxAwareDataSources.wrap(e.dataSource());
        }
        DataSource host = hostDataSource.getIfAvailable();
        if (host == null) {
            throw new IllegalStateException(
                    "未找到可用的服务端存储：宿主未提供 DataSource Bean。"
                            + "请改用 linkora.server.store=sqlite（默认，内嵌文件库），"
                            + "或提供 DataSource Bean 后使用 store=datasource");
        }
        String store = properties.getStore() == null ? "sqlite" : properties.getStore();
        if ("datasource".equalsIgnoreCase(store.trim())) {
            log.info("Linkora 服务端存储模式: datasource（与宿主业务同库同事务）");
            return TxAwareDataSources.wrap(host);
        }
        throw new IllegalStateException(
                "linkora.server.store 仅支持 sqlite | datasource，当前: " + store);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientRegistry linkoraClientRegistry(
            @Qualifier(SERVER_STORAGE_BEAN) DataSource dataSource) {
        return new JdbcClientRegistry(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageRepository linkoraMessageRepository(
            @Qualifier(SERVER_STORAGE_BEAN) DataSource dataSource) {
        return new JdbcMessageRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeliveryRepository linkoraDeliveryRepository(
            @Qualifier(SERVER_STORAGE_BEAN) DataSource dataSource) {
        return new JdbcDeliveryRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientCursorRepository linkoraClientCursorRepository(
            @Qualifier(SERVER_STORAGE_BEAN) DataSource dataSource) {
        return new JdbcClientCursorRepository(dataSource);
    }

    /**
     * 组装 {@link LinkoraServer}：auto-migrate 开启且为 datasource 模式时先迁移
     * schema（sqlite 模式已在内嵌库 Bean 创建时迁移）；认证器回退等组装核心复用
     * linkora-spring 的 {@link com.linkora.spring.LinkoraServerAssembler}；
     * 宿主已定义 LinkoraServer bean 时本方法不生效。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(LinkoraServer.class)
    public LinkoraServer linkoraServer(
            @Qualifier(SERVER_STORAGE_BEAN) DataSource dataSource,
            ClientRegistry clientRegistry,
            MessageRepository messageRepository,
            DeliveryRepository deliveryRepository,
            ClientCursorRepository cursorRepository,
            org.springframework.beans.factory.ObjectProvider<ClientAuthenticator> authenticatorProvider,
            org.springframework.beans.factory.ObjectProvider<IngestListener> ingestListenerProvider,
            org.springframework.beans.factory.ObjectProvider<com.linkora.core.uplink.UplinkHandler> uplinkHandlerProvider,
            LinkoraServerProperties properties) {
        if (properties.isAutoMigrate()) {
            // 在 server bean 装配（随后的 start）之前完成 schema 迁移；
            // sqlite 模式下迁移已随内嵌库创建完成，重复执行幂等
            JdbcServerSchema.migrate(dataSource);
        }
        List<IngestListener> ingestListeners = ingestListenerProvider.stream()
                .collect(java.util.stream.Collectors.toList());
        List<com.linkora.core.uplink.UplinkHandler> uplinkHandlers = uplinkHandlerProvider.stream()
                .collect(java.util.stream.Collectors.toList());
        return LinkoraServerAssembler.build(
                authenticatorProvider.getIfAvailable(),
                clientRegistry, messageRepository, deliveryRepository, cursorRepository,
                toServerConfig(properties), null, ingestListeners, uplinkHandlers,
                properties.isAllowAnonymous());
    }

    private static ServerConfig toServerConfig(LinkoraServerProperties p) {
        ServerConfig.Builder builder = ServerConfig.builder();
        if (p.getSweepIntervalMs() != null) {
            builder.sweepIntervalMs(p.getSweepIntervalMs());
        }
        if (p.getPushBatchSize() != null) {
            builder.pushBatchSize(p.getPushBatchSize());
        }
        if (p.getMaxDeliveryRetries() != null) {
            builder.maxDeliveryRetries(p.getMaxDeliveryRetries());
        }
        if (p.getRetryBackoffBaseMs() != null) {
            builder.retryBackoffBaseMs(p.getRetryBackoffBaseMs());
        }
        if (p.getRetryBackoffMaxMs() != null) {
            builder.retryBackoffMaxMs(p.getRetryBackoffMaxMs());
        }
        if (p.getPushPacingBaseMs() != null) {
            builder.pushPacingBaseMs(p.getPushPacingBaseMs());
        }
        if (p.getPushPacingMaxMs() != null) {
            builder.pushPacingMaxMs(p.getPushPacingMaxMs());
        }
        if (p.getMessageSizeLimitBytes() != null) {
            builder.messageSizeLimitBytes(p.getMessageSizeLimitBytes());
        }
        if (p.getRetentionDays() != null) {
            builder.retentionDays(p.getRetentionDays());
        }
        if (p.getAlertWebhookUrl() != null && !p.getAlertWebhookUrl().trim().isEmpty()) {
            builder.alertWebhookUrl(p.getAlertWebhookUrl());
            if (p.getAlertWebhookType() != null && !p.getAlertWebhookType().trim().isEmpty()) {
                builder.alertWebhookType(p.getAlertWebhookType());
            }
        }
        if (p.getInboxStuckMinutes() != null) {
            builder.inboxStuckMinutes(p.getInboxStuckMinutes());
        }
        return builder.build();
    }

    /** 启动后按 linkora.server.register-clients 注册客户端（幂等）。 */
    @Bean
    public ApplicationRunner linkoraClientRegistrar(ClientRegistry registry,
                                                    LinkoraServerProperties properties) {
        return args -> {
            for (Map.Entry<String, Boolean> entry : properties.getRegisterClients().entrySet()) {
                String clientId = entry.getKey();
                boolean enabled = Boolean.TRUE.equals(entry.getValue());
                if (!registry.findByClientId(clientId).isPresent()) {
                    registry.register(new ClientRegistration(clientId, enabled,
                            System.currentTimeMillis()));
                    log.info("已按配置注册客户端: {} (enabled={})", clientId, enabled);
                }
            }
        };
    }

    // ---------------- 三端点 Servlet ----------------

    @Bean
    public ServletRegistrationBean<SsePushServlet> linkoraSsePushServlet(
            LinkoraServer server, LinkoraServerProperties properties) {
        ServletRegistrationBean<SsePushServlet> bean = new ServletRegistrationBean<SsePushServlet>(
                new SsePushServlet(server.sseSubscribeHandler(), server.sseConnectionRegistry(),
                        properties.getHeartbeatIntervalMs() == null ? 15_000L
                                : properties.getHeartbeatIntervalMs(),
                        properties.getReconnectHintMs() == null ? 3_000L
                                : properties.getReconnectHintMs(),
                        server.getMaxOutboundBytesPerConnection()),
                properties.getEventsPath());
        bean.setName("linkoraSsePushServlet");
        bean.setAsyncSupported(true);
        return bean;
    }

    @Bean
    public ServletRegistrationBean<LinkoraEndpointServlet> linkoraIngestServlet(
            LinkoraServer server, LinkoraServerProperties properties) {
        ServletRegistrationBean<LinkoraEndpointServlet> bean =
                new ServletRegistrationBean<LinkoraEndpointServlet>(
                        new LinkoraEndpointServlet(server.ingestHttpHandler(), server.getMaxBodyBytes()),
                        properties.getMessagesPath());
        bean.setName("linkoraIngestServlet");
        return bean;
    }

    @Bean
    public ServletRegistrationBean<LinkoraEndpointServlet> linkoraAckServlet(
            LinkoraServer server, LinkoraServerProperties properties) {
        ServletRegistrationBean<LinkoraEndpointServlet> bean =
                new ServletRegistrationBean<LinkoraEndpointServlet>(
                        new LinkoraEndpointServlet(server.ackHttpHandler(), server.getMaxBodyBytes()),
                        properties.getAcksPath());
        bean.setName("linkoraAckServlet");
        return bean;
    }
}
