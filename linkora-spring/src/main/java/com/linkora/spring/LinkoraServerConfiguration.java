package com.linkora.spring;

import com.linkora.core.serialize.PayloadSerializer;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.auth.ClientAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring 宿主装配辅助：容器内存在四个存储 SPI bean 时自动组装 {@link LinkoraServer}。
 *
 * <p>使用方式：宿主提供 {@link ClientRegistry} / {@link MessageRepository} /
 * {@link DeliveryRepository} / {@link ClientCursorRepository} 四个 bean
 * （可直接用 linkora-server-storage-jdbc 的 JDBC 实现，也可自实现接管事务，
 * 见 docs/storage-spi.md），本配置负责组装与生命周期
 * （上下文刷新时 start，销毁时 close，两者均幂等）。</p>
 *
 * <p>可选项：</p>
 * <ul>
 *   <li>{@link ClientAuthenticator} bean —— 缺省回退 AllowAll 并输出警告，
 *       生产环境务必提供真实认证器（见 docs/security.md）；</li>
 *   <li>{@link ServerConfig} bean —— 缺省使用默认参数；</li>
 *   <li>{@link PayloadSerializer} bean —— 需要发布对象负载时提供。</li>
 * </ul>
 *
 * <p>本类只依赖 spring-context，不依赖 Spring Boot；Boot 宿主请优先使用
 * linkora-spring-boot-starter（含配置属性与 Servlet 自动注册）。</p>
 */
@Configuration
public class LinkoraServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServerConfiguration.class);

    @Bean(initMethod = "start", destroyMethod = "close")
    public LinkoraServer linkoraServer(
            ClientRegistry clientRegistry,
            MessageRepository messageRepository,
            DeliveryRepository deliveryRepository,
            ClientCursorRepository cursorRepository,
            ObjectProvider<ClientAuthenticator> authenticatorProvider,
            ObjectProvider<ServerConfig> configProvider,
            ObjectProvider<PayloadSerializer> payloadSerializerProvider) {

        ClientAuthenticator authenticator = authenticatorProvider.getIfAvailable();
        if (authenticator == null) {
            log.warn("容器内未提供 ClientAuthenticator，回退为 AllowAllClientAuthenticator；"
                    + "该认证器放行全部请求，仅限本地开发与测试，生产环境必须替换"
                    + "（参见 docs/security.md 的 HMAC 方案）");
            authenticator = new AllowAllClientAuthenticator();
        }

        LinkoraServer.Builder builder = LinkoraServer.builder()
                .clientAuthenticator(authenticator)
                .clientRegistry(clientRegistry)
                .messageRepository(messageRepository)
                .deliveryRepository(deliveryRepository)
                .cursorRepository(cursorRepository);

        ServerConfig config = configProvider.getIfAvailable();
        if (config != null) {
            builder.config(config);
        }
        PayloadSerializer serializer = payloadSerializerProvider.getIfAvailable();
        if (serializer != null) {
            builder.payloadSerializer(serializer);
        }

        log.info("LinkoraServer 已由 Spring 容器装配（认证器: {}）",
                authenticator.getClass().getSimpleName());
        return builder.build();
    }
}
