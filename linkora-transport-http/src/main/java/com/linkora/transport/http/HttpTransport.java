package com.linkora.transport.http;

/**
 * HTTP 传输 SPI。默认实现为 {@link OkHttpTransport}；
 * 宿主可通过提供自己的实现替换 HTTP 引擎（例如复用宿主连接池）。
 *
 * <p>实现要求：线程安全；失败必须以 {@link TransportException} 抛出并完成错误
 * 分类，不得吞异常；传输层自身不做自动重试。</p>
 */
public interface HttpTransport extends AutoCloseable {

    /**
     * 同步执行请求并读取完整响应体。
     *
     * @return 响应（任何 2xx~5xx 状态码都以正常返回表达，由调用方判定成败）
     * @throws TransportException 网络/超时/中断等非 HTTP 响应类失败
     */
    TransportResponse execute(TransportRequest request) throws TransportException;

    /**
     * 释放底层资源（连接池、线程池）。关闭后不得再调用 execute。
     */
    @Override
    void close();
}
