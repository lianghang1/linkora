package com.linkora.transport.sse;

/**
 * 重连退避策略 SPI。实现必须线程安全且无副作用。
 */
public interface ReconnectPolicy {

    /**
     * 计算下一次重连的延迟。
     *
     * @param attempt 连续失败次数（从 1 开始，成功建连后归零）
     * @param serverSuggestedMs 服务端 retry: 字段建议值；当前 okhttp-sse 不透出该字段，
     *                          保留参数供自定义解析扩展使用，默认实现恒为 null
     * @return 延迟毫秒数，必须 >= 0
     */
    long nextDelayMs(int attempt, Long serverSuggestedMs);
}
