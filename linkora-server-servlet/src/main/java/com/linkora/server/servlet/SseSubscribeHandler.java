package com.linkora.server.servlet;

/**
 * SSE 订阅处理 SPI。由上层（linkora-server）实现真实鉴权与注册逻辑；
 * Servlet 适配器只负责连接生命周期与帧写出。
 */
public interface SseSubscribeHandler {

    /**
     * 决定是否接受该订阅。返回 false 时适配器回写 401 并断开。
     * 实现 MUST 依据认证上下文校验 clientId，不能只信任请求参数。
     *
     * @param clientId    客户端声明标识（请求头 X-Linkora-Client-Id 或查询参数）
     * @param lastEventId 客户端携带的续传游标（Last-Event-ID 请求头），可为 null
     * @param connection  新建立的连接，接受订阅后即可用于推送
     * @return true 表示接受；false 表示拒绝（回写 401）
     */
    boolean onSubscribe(String clientId, String lastEventId, SseClientConnection connection);

    /**
     * 连接断开回调（客户端断开、IO 错误或服务端主动关闭）。
     * {@code cause} 为 null 表示服务端主动关闭。
     */
    void onDisconnect(String clientId, SseClientConnection connection,
                      Throwable cause /* 可为 null，表示服务端主动关闭 */);
}
