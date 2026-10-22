package com.linkora.spring;

import com.linkora.core.serialize.PayloadSerializer;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.core.uplink.UplinkHandler;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.pipeline.IngestListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Spring 宿主装配辅助：容器内存在四个存储 SPI bean 时自动组装 {@link LinkoraServer}。
 *
 * <p>使用方式：宿主提供 {@link ClientRegistry} / {@link MessageRepository} /
 * {@link DeliveryRepository} / {@link ClientCursorRepository} 四个 bean
 * （可直接用 linkora-server 内置的 JDBC 实现，也可自实现接管事务，
 * 见 docs/storage-spi.md），本配置负责组装与生命周期
 * （上下文刷新时 start，销毁时 close，两者均幂等）。</p>
 *
 * <p>可选项：</p>
 * <ul>
 *   <li>{@link ClientAuthenticator} bean —— 缺省回退 AllowAll 并输出警告，
 *       生产环境务必提供真实认证器（见 docs/security.md）；</li>
 *   <li>{@link ServerConfig} bean —— 缺省使用默认参数；</li>
 *   <li>{@link PayloadSerializer} bean —— 需要发布对象负载时提供；</li>
 *   <li>{@link IngestListener} bean —— 可注册多个，全部按顺序接入 LinkoraServer；</li>
 *   <li>{@link UplinkHandler} bean —— 推荐的上行接入方式：声明即自动经
 *       {@code UplinkRouter} 按 messageType 分发，无需自写监听器与路由。</li>
 * </ul>
 *
 * <p>本类只依赖 spring-context，不依赖 Spring Boot；Boot 宿主请优先使用
 * linkora-spring-boot-starter（含配置属性与 Servlet 自动注册）。</p>
 *
 * @author lianghang
 */
@Configuration
public class LinkoraServerConfiguration {

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
     * @param ingestListenerProvider 上行消息业务监听器提供者（可选，可注册多个）
     * @param uplinkHandlerProvider 上行消息处理器提供者（可选，推荐方式：
     *        非空时自动经 UplinkRouter 按 messageType 分发）
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
            ObjectProvider<IngestListener> ingestListenerProvider,
            ObjectProvider<UplinkHandler> uplinkHandlerProvider) {

        List<IngestListener> ingestListeners = ingestListenerProvider.stream()
                .collect(Collectors.toList());
        List<UplinkHandler> uplinkHandlers = uplinkHandlerProvider.stream()
                .collect(Collectors.toList());
        return LinkoraServerAssembler.build(
                authenticatorProvider.getIfAvailable(),
                clientRegistry, messageRepository, deliveryRepository, cursorRepository,
                configProvider.getIfAvailable(),
                payloadSerializerProvider.getIfAvailable(),
                ingestListeners, uplinkHandlers);
    }
}
