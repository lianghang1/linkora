package com.linkora.client;

/**
 * 下行消息业务监听器。
 *
 * <p>语义：正常返回且未显式调用 ack 方法 → 自动标记业务处理成功；
 * 调用 {@code message.ackProcessed()} → 立即标记成功；
 * 调用 {@code message.ackFailed(code, msg)} 或抛出异常 → 标记失败并按配置重试。
 * 单个监听器异常不会影响客户端其他消息的处理。</p>
 *
 * @author lianghang
 */
public interface MessageListener {

    void onMessage(LinkoraReceivedMessage message);
}
