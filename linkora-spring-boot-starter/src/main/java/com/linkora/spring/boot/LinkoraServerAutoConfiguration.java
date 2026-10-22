package com.linkora.spring.boot;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 服务端自动装配：宿主提供 {@link DataSource} 即获得 LinkoraServer 与三端点 Servlet。
 *
 * <p>默认使用 linkora-server 内置的 JDBC 仓储；宿主定义自己的
 * Repository/Registry bean（如借助 TransactionAwareDataSourceProxy 接管事务，
 * 见 docs/storage-spi.md）即可整体覆盖。宿主定义自己的 {@link LinkoraServer}
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
@ConditionalOnSingleCandidate(DataSource.class)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(LinkoraServerProperties.class)
public class LinkoraServerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ClientRegistry linkoraClientRegistry(DataSource dataSource) {
        return new JdbcClientRegistry(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageRepository linkoraMessageRepository(DataSource dataSource) {
        return new JdbcMessageRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public DeliveryRepository linkoraDeliveryRepository(DataSource dataSource) {
        return new JdbcDeliveryRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public ClientCursorRepository linkoraClientCursorRepository(DataSource dataSource) {
        return new JdbcClientCursorRepository(dataSource);
    }

    /**
     * 组装 {@link LinkoraServer}：auto-migrate 开启时先迁移 schema；
     * 认证器回退等组装核心复用 linkora-spring 的 {@link com.linkora.spring.LinkoraServerAssembler}；
     * 宿主已定义 LinkoraServer bean 时本方法不生效。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean(LinkoraServer.class)
    public LinkoraServer linkoraServer(DataSource dataSource,
                                       ClientRegistry clientRegistry,
                                       MessageRepository messageRepository,
                                       DeliveryRepository deliveryRepository,
                                       ClientCursorRepository cursorRepository,
                                       org.springframework.beans.factory.ObjectProvider<ClientAuthenticator> authenticatorProvider,
                                       org.springframework.beans.factory.ObjectProvider<IngestListener> ingestListenerProvider,
                                       org.springframework.beans.factory.ObjectProvider<com.linkora.core.uplink.UplinkHandler> uplinkHandlerProvider,
                                       LinkoraServerProperties properties) {
        if (properties.isAutoMigrate()) {
            // 在 server bean 装配（随后的 start）之前完成 schema 迁移
            JdbcServerSchema.migrate(dataSource);
        }
        List<IngestListener> ingestListeners = ingestListenerProvider.stream()
                .collect(java.util.stream.Collectors.toList());
        List<com.linkora.core.uplink.UplinkHandler> uplinkHandlers = uplinkHandlerProvider.stream()
                .collect(java.util.stream.Collectors.toList());
        return LinkoraServerAssembler.build(
                authenticatorProvider.getIfAvailable(),
                clientRegistry, messageRepository, deliveryRepository, cursorRepository,
                toServerConfig(properties), null, ingestListeners, uplinkHandlers);
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
        if (p.getRetentionEnabled() != null) {
            builder.retentionEnabled(p.getRetentionEnabled());
        }
        if (p.getRetentionMs() != null) {
            builder.retentionMs(p.getRetentionMs());
        }
        if (p.getRetentionBatchSize() != null) {
            builder.retentionBatchSize(p.getRetentionBatchSize());
        }
        if (p.getExpirySweepBatchSize() != null) {
            builder.expirySweepBatchSize(p.getExpirySweepBatchSize());
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
                                : properties.getReconnectHintMs()),
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
                        new LinkoraEndpointServlet(server.ingestHttpHandler()),
                        properties.getMessagesPath());
        bean.setName("linkoraIngestServlet");
        return bean;
    }

    @Bean
    public ServletRegistrationBean<LinkoraEndpointServlet> linkoraAckServlet(
            LinkoraServer server, LinkoraServerProperties properties) {
        ServletRegistrationBean<LinkoraEndpointServlet> bean =
                new ServletRegistrationBean<LinkoraEndpointServlet>(
                        new LinkoraEndpointServlet(server.ackHttpHandler()),
                        properties.getAcksPath());
        bean.setName("linkoraAckServlet");
        return bean;
    }
}
