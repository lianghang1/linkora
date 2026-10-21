package com.linkora.transport.sse;

/**
 * SSE 事件回调。所有回调由客户端单线程顺序投递，实现方不得在回调内
 * 执行长时间阻塞操作；抛出的异常会被捕获并记录，不影响重连机制。
 *
 * <p>心跳约定：服务端心跳以 {@code event: hb} 类型事件发送（okhttp-sse 不透出
 * 注释行，事件型心跳可同时驱动客户端空闲检测）。</p>
 */
public interface SseListener {

    /** 连接建立成功。 */
    void onOpen();

    /**
     * 收到事件。{@code id} 可为 null（本连接尚未收到过 id）；
     * {@code type} 为事件类型（服务端心跳为 "hb"）。
     */
    void onEvent(String id, String type, String data);

    /** 服务端正常关闭了事件流（客户端随后会自动重连，除非本客户端已被手动关闭）。 */
    void onClosed();

    /**
     * 连接失败（连接拒绝、HTTP 错误、IO 异常、空闲超时取消等）。
     * {@code httpCode} 为 null 表示非 HTTP 响应类失败。
     */
    void onFailure(Throwable error, Integer httpCode);
}
