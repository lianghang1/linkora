package com.linkora.server.servlet;

/** POST 端点处理 SPI：由 linkora-server 实现（认证 + 协议校验 + 持久化）。 */
public interface LinkoraHttpHandler {

    LinkoraHttpResult handle(LinkoraHttpRequest request);
}
