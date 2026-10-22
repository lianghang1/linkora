package com.linkora.core.uplink;

import com.linkora.core.message.LinkoraMessage;

/**
 * 上行消息处理器（服务端业务侧契约）：每个实现类负责一种消息类型的业务处理，
 * 由路由器按 {@link #messageType()} 转发。
 *
 * <p>Spring 宿主将实现类声明为 bean（Boot 用 @Component、MVC 用 @Service）即可
 * 自动接入 LinkoraServer，无需任何额外装配；非 Spring 宿主可通过
 * {@code UplinkRouter} 手动组装。</p>
 *
 * @author lianghang
 */
public interface UplinkHandler {

    /** 本处理器负责的上行消息类型（与客户端发送的 messageType 一致）。 */
    String messageType();

    /** 处理一条已持久化的上行消息（摄入线程同步调用，耗时处理请自行异步化）。 */
    void handle(LinkoraMessage message);
}
