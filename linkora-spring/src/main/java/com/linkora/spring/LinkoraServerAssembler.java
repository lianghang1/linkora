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

import java.util.List;

/**
 * LinkoraServer 组装核心：认证器回退、builder 装配与监听器接入的唯一实现。
 *
 * <p>供 Spring 宿主 {@link LinkoraServerConfiguration} 与 Boot starter
 * 自动配置共用，避免两套装配代码平行维护；本类不依赖任何 Spring 类型。</p>
 *
 * @author lianghang
 */
public final class LinkoraServerAssembler {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServerAssembler.class);

    private LinkoraServerAssembler() {
    }

    /**
     * 组装 {@link LinkoraServer}：认证器为 null 时回退 AllowAll 并告警（仅限本地
     * 开发与测试，生产环境必须提供真实认证器）；config/serializer 为 null 时使用
     * 默认；监听器按传入顺序接入。
     */
    public static LinkoraServer build(ClientAuthenticator authenticator,
                                      ClientRegistry clientRegistry,
                                      MessageRepository messageRepository,
                                      DeliveryRepository deliveryRepository,
                                      ClientCursorRepository cursorRepository,
                                      ServerConfig config,
                                      PayloadSerializer payloadSerializer,
                                      List<IngestListener> ingestListeners) {
        if (authenticator == null) {
            log.warn("未提供 ClientAuthenticator，回退为 AllowAllClientAuthenticator；"
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
        if (config != null) {
            builder.config(config);
        }
        if (payloadSerializer != null) {
            builder.payloadSerializer(payloadSerializer);
        }
        if (ingestListeners != null && !ingestListeners.isEmpty()) {
            builder.ingestListeners(ingestListeners);
            for (IngestListener listener : ingestListeners) {
                log.info("上行消息监听器已接入: {}", listener.getClass().getSimpleName());
            }
        }

        log.info("LinkoraServer 已装配（认证器: {}）", authenticator.getClass().getSimpleName());
        return builder.build();
    }
}
