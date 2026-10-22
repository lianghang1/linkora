package com.linkora.spring;

import com.linkora.core.serialize.PayloadSerializer;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.core.uplink.UplinkHandler;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.pipeline.IngestListener;
import com.linkora.server.uplink.UplinkRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LinkoraServer 组装核心：认证器校验、builder 装配与监听器接入的唯一实现。
 *
 * <p>供 Spring 宿主 {@link LinkoraServerConfiguration} 与 Boot starter
 * 自动配置共用，避免两套装配代码平行维护；本类不依赖任何 Spring 类型。</p>
 *
 * <p><b>安全默认值</b>：缺少 {@link ClientAuthenticator} 时<b>直接启动失败</b>，
 * 而不是回退成"放行全部请求"——SSE 端点通常直接暴露在网络上，一个配置漏项
 * 就意味着任何人都能订阅任意 clientId 的下行数据。本地开发需要匿名访问时，
 * 必须显式传 {@code allowAnonymous=true}（Boot 属性
 * {@code linkora.server.allow-anonymous}），并会在启动日志里留痕。</p>
 *
 * @author lianghang
 */
public final class LinkoraServerAssembler {

    private static final Logger log = LoggerFactory.getLogger(LinkoraServerAssembler.class);

    private LinkoraServerAssembler() {
    }

    /**
     * 组装 {@link LinkoraServer}：uplinkHandlers 非空时自动追加一个
     * {@link UplinkRouter} 按 messageType 分发；未提供认证器时启动失败。
     */
    public static LinkoraServer build(ClientAuthenticator authenticator,
                                      ClientRegistry clientRegistry,
                                      MessageRepository messageRepository,
                                      DeliveryRepository deliveryRepository,
                                      ClientCursorRepository cursorRepository,
                                      ServerConfig config,
                                      PayloadSerializer payloadSerializer,
                                      List<IngestListener> ingestListeners,
                                      List<UplinkHandler> uplinkHandlers) {
        return build(authenticator, clientRegistry, messageRepository, deliveryRepository,
                cursorRepository, config, payloadSerializer, ingestListeners, uplinkHandlers, false);
    }

    /**
     * 组装 {@link LinkoraServer}（显式控制"匿名放行"回退）。
     *
     * @param allowAnonymous 未提供认证器时是否允许回退为"放行全部请求"。
     *                       默认必须为 {@code false}：缺认证器直接启动失败，
     *                       而不是把整条 SSE 通道裸奔在公网上。
     */
    public static LinkoraServer build(ClientAuthenticator authenticator,
                                      ClientRegistry clientRegistry,
                                      MessageRepository messageRepository,
                                      DeliveryRepository deliveryRepository,
                                      ClientCursorRepository cursorRepository,
                                      ServerConfig config,
                                      PayloadSerializer payloadSerializer,
                                      List<IngestListener> ingestListeners,
                                      List<UplinkHandler> uplinkHandlers,
                                      boolean allowAnonymous) {
        List<IngestListener> listeners = ingestListeners;
        if (uplinkHandlers != null && !uplinkHandlers.isEmpty()) {
            listeners = ingestListeners == null
                    ? new ArrayList<IngestListener>()
                    : new ArrayList<IngestListener>(ingestListeners);
            listeners.add(new UplinkRouter(uplinkHandlers));
        }
        return doBuild(authenticator, clientRegistry, messageRepository, deliveryRepository,
                cursorRepository, config, payloadSerializer, listeners, allowAnonymous);
    }

    /** 组装 {@link LinkoraServer}（不带上行处理器）。 */
    public static LinkoraServer build(ClientAuthenticator authenticator,
                                      ClientRegistry clientRegistry,
                                      MessageRepository messageRepository,
                                      DeliveryRepository deliveryRepository,
                                      ClientCursorRepository cursorRepository,
                                      ServerConfig config,
                                      PayloadSerializer payloadSerializer,
                                      List<IngestListener> ingestListeners) {
        return doBuild(authenticator, clientRegistry, messageRepository, deliveryRepository,
                cursorRepository, config, payloadSerializer, ingestListeners, false);
    }

    /**
     * 组装 {@link LinkoraServer}（不带上行处理器 + 匿名开关）。
     *
     * @param allowAnonymous 未提供认证器时是否允许回退为"放行全部请求"
     */
    public static LinkoraServer build(ClientAuthenticator authenticator,
                                      ClientRegistry clientRegistry,
                                      MessageRepository messageRepository,
                                      DeliveryRepository deliveryRepository,
                                      ClientCursorRepository cursorRepository,
                                      ServerConfig config,
                                      PayloadSerializer payloadSerializer,
                                      List<IngestListener> ingestListeners,
                                      boolean allowAnonymous) {
        return doBuild(authenticator, clientRegistry, messageRepository, deliveryRepository,
                cursorRepository, config, payloadSerializer, ingestListeners, allowAnonymous);
    }

    /**
     * 唯一实际装配点：认证器校验（fail-fast 或显式匿名）、config/serializer 缺省、
     * 监听器按顺序接入。
     */
    private static LinkoraServer doBuild(ClientAuthenticator authenticator,
                                         ClientRegistry clientRegistry,
                                         MessageRepository messageRepository,
                                         DeliveryRepository deliveryRepository,
                                         ClientCursorRepository cursorRepository,
                                         ServerConfig config,
                                         PayloadSerializer payloadSerializer,
                                         List<IngestListener> ingestListeners,
                                         boolean allowAnonymous) {
        if (authenticator == null) {
            if (!allowAnonymous) {
                throw new IllegalStateException("未提供 ClientAuthenticator，拒绝启动："
                        + "缺少认证器时服务端会放行全部请求（SSE 通道直接暴露在网络上）。"
                        + "生产环境请配置 HmacClientAuthenticator（见 docs/security.md）；"
                        + "本地开发可显式设置 linkora.server.allow-anonymous=true。");
            }
            log.warn("未提供 ClientAuthenticator 且显式允许匿名（allow-anonymous=true），"
                    + "回退为 AllowAllClientAuthenticator：该认证器放行全部请求，"
                    + "仅限本地开发与测试，生产环境必须替换（参见 docs/security.md 的 HMAC 方案）");
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
