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
import com.linkora.server.pipeline.IngestListener;
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
 *
 * @author lianghang
 */
@Configuration
public class LinkoraServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServerConfiguration.class);

    /**
     * 从容器内四个存储 SPI bean 组装 {@link LinkoraServer}。
     *
     * <p>认证器、{@link ServerConfig}、{@link PayloadSerializer} 均为可选项：
     * 认证器缺失时回退 AllowAll 并输出警告，其余缺失时使用默认实现。</p>
     *
     * @param clientRegistry 在线客户端注册表（必选）
     * @param messageRepository 消息持久化仓库（必选）
     * @param deliveryRepository 下行投递记录仓库（必选）
     * @param cursorRepository 客户端游标仓库（必选）
     * @param authenticatorProvider 认证器提供者（可选）
     * @param configProvider 服务端配置提供者（可选）
     * @param payloadSerializerProvider 对象负载序列化器提供者（可选）
     * @param ingestListenerProvider 上行消息业务监听器提供者（可选）
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public LinkoraServer linkoraServer(
            ClientRegistry clientRegistry,
            MessageRepository messageRepository,
            DeliveryRepository deliveryRepository,
            ClientCursorRepository cursorRepository,
            ObjectProvider<ClientAuthenticator> authenticatorProvider,
            ObjectProvider<ServerConfig> configProvider,
            ObjectProvider<PayloadSerializer> payloadSerializerProvider,
            ObjectProvider<IngestListener> ingestListenerProvider) {

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
        IngestListener ingestListener = ingestListenerProvider.getIfAvailable();
        if (ingestListener != null) {
            builder.ingestListener(ingestListener);
            log.info("上行消息监听器已接入: {}", ingestListener.getClass().getSimpleName());
        }

        log.info("LinkoraServer 已由 Spring 容器装配（认证器: {}）",
                authenticator.getClass().getSimpleName());
        return builder.build();
    }
}
