package com.linkora.example.boot.service.uplink;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.server.pipeline.IngestListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上行消息转发器：实现框架的 {@link IngestListener}（唯一入口），再按
 * messageType 路由到注册的 {@link UplinkHandler}——服务端的"消息分发中枢"。
 *
 * <p>注册方式：Spring 注入容器内全部 {@link UplinkHandler} bean 自动完成；
 * 本 bean 存在时 linkora-spring-boot-starter 将其接入 LinkoraServer。</p>
 *
 * @author lianghang
 */
@Service
public class UplinkMessageDispatcher implements IngestListener {

    private static final Logger log = LoggerFactory.getLogger(UplinkMessageDispatcher.class);

    private final Map<String, UplinkHandler> handlers = new ConcurrentHashMap<String, UplinkHandler>();

    public UplinkMessageDispatcher(List<UplinkHandler> uplinkHandlers) {
        for (UplinkHandler handler : uplinkHandlers) {
            handlers.put(handler.messageType(), handler);
            log.info("上行消息处理器已注册: {} -> {}",
                    handler.messageType(), handler.getClass().getSimpleName());
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
