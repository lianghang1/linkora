package com.linkora.server.uplink;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.uplink.UplinkHandler;
import com.linkora.server.pipeline.IngestListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上行消息路由器：实现 {@link IngestListener}（框架唯一摄入入口），按
 * messageType 转发给对应的 {@link UplinkHandler}——服务端"上行分发中枢"
 * 的内置实现，宿主只需提供 handler，无需自写分发器。
 *
 * <p>Spring 宿主：装配层自动收集容器内全部 {@link UplinkHandler} bean 组装
 * 本类接入 LinkoraServer；非 Spring 宿主：手动
 * {@code new UplinkRouter(handlerList)} 后按 {@code ingestListener} 注册。</p>
 *
 * @author lianghang
 */
public final class UplinkRouter implements IngestListener {

    private static final Logger log = LoggerFactory.getLogger(UplinkRouter.class);

    private final Map<String, UplinkHandler> handlers = new ConcurrentHashMap<String, UplinkHandler>();

    /**
     * @param uplinkHandlers 上行处理器列表，同一 messageType 重复注册视为装配错误
     */
    public UplinkRouter(List<UplinkHandler> uplinkHandlers) {
        if (uplinkHandlers != null) {
            for (UplinkHandler handler : uplinkHandlers) {
                UplinkHandler previous = handlers.put(handler.messageType(), handler);
                if (previous != null) {
                    throw new IllegalArgumentException("上行消息类型重复注册: " + handler.messageType()
                            + " -> " + previous.getClass().getName() + " / " + handler.getClass().getName());
                }
                log.info("上行消息处理器已注册: {} -> {}",
                        handler.messageType(), handler.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void onIngested(LinkoraMessage message) {
        UplinkHandler handler = handlers.get(message.getMessageType());
        if (handler == null) {
            log.debug("无处理器的上行消息，忽略: type={}, messageId={}",
                    message.getMessageType(), message.getMessageId());
            return;
        }
        handler.handle(message);
    }
}
