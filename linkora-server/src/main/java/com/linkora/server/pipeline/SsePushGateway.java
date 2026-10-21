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
}
