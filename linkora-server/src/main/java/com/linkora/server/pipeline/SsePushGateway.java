package com.linkora.server.pipeline;

/**
 * SSE 推送网关 SPI：向指定客户端写出一条事件帧。
 * 抽象掉 SseConnectionRegistry，便于单元测试注入记录型网关。
 *
 * @author lianghang
 */
public interface SsePushGateway {

    /**
     * 向客户端所有活动连接推送事件。
     *
     * @param eventId SSE 事件 id（投递序号），可为 null
     * @return 成功写出的连接数（0 表示客户端当前不在线）
     */
    int push(String clientId, String eventId, String eventType, String data);

    /** 客户端当前是否至少有一条活动连接。 */
    boolean isConnected(String clientId);

    /** 客户端当前活动连接数（断开回调中用于判断是否彻底离线）。 */
    int connectionCount(String clientId);

    /** 当前有活动连接的客户端 ID 快照。 */
    java.util.Set<String> activeClientIds();

    /** 关闭全部连接（服务端优雅停机）。 */
    void closeAll();

    /**
     * 强制断开指定客户端的全部 SSE 连接（管理接口：处置"假在线"连接）。
     *
     * @return 断开的连接数（0 表示该客户端本就不在线）
     */
    int disconnectClient(String clientId);

    /**
     * 向客户端所有活动连接发送管理指令（SSE {@code ctrl} 事件）。
     *
     * <p>与业务消息的本质区别：不落库、不重试、不进客户端业务队列——
     * 仅对在线客户端实时生效，未送达即返回 0，由运维再次触发。</p>
     *
     * @param data 指令 JSON（{"cmd":..., "params":{...}}）
     * @return 成功写出的连接数（0 表示客户端当前不在线）
     */
    int sendControl(String clientId, String data);
}
