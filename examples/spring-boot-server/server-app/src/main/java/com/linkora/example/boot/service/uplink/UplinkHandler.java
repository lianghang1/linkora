package com.linkora.example.boot.service.uplink;

import com.linkora.core.message.LinkoraMessage;

/**
 * 上行消息处理器（服务端业务侧）：每个实现类负责一种消息类型的业务处理，
 * 由 {@link UplinkMessageDispatcher} 按 messageType 转发。
 *
 * @author lianghang
 */
public interface UplinkHandler {

    /** 本处理器负责的上行消息类型（与客户端发送的 topic 一致）。 */
    String messageType();

    /** 处理一条已持久化的上行消息（摄入线程同步调用，耗时处理请自行异步化）。 */
    void handle(LinkoraMessage message);
}
