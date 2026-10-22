package com.linkora.server.servlet;

import java.util.Map;

/**
 * SSE 订阅处理 SPI。由上层（linkora-server）实现真实鉴权与注册逻辑；
 * Servlet 适配器只负责连接生命周期与帧写出。
 *
 * @author lianghang
 */
public interface SseSubscribeHandler {

    /**
     * 决定是否接受该订阅。返回 false 时适配器回写 401 并断开。
     * 实现 MUST 依据认证上下文校验 clientId，不能只信任请求参数。
     * 接受订阅时 MUST 在返回前调用 {@code connection.markReadyForPush()}，
     * 否则该连接不会收到任何广播推送（未就绪连接对广播不可见）。
     *
     * @param clientId    客户端声明标识（请求头 X-Linkora-Client-Id 或查询参数）
     * @param lastEventId 客户端携带的续传游标（Last-Event-ID 请求头），可为 null
     * @param headers     请求头快照（头名保留原始大小写，取每个头第一个值），用于应用层认证
     * @param connection  新建立的连接，接受订阅后即可用于推送
     * @return true 表示接受；false 表示拒绝（回写 401）
     */
    boolean onSubscribe(String clientId, String lastEventId,
                        Map<String, String> headers, SseClientConnection connection);

    /**
     * 连接断开回调（客户端断开、IO 错误或服务端主动关闭）。
     * {@code cause} 为 null 表示服务端主动关闭。
     */
    void onDisconnect(String clientId, SseClientConnection connection,
                      Throwable cause /* 可为 null，表示服务端主动关闭 */);
}
