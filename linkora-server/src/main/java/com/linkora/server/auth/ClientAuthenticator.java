package com.linkora.server.auth;

/**
 * 客户端身份认证 SPI。所有入口（SSE 订阅、消息上报、ACK）都必须经过认证，
 * 服务端 MUST 依据认证结果判定 clientId 权限，不能只信任请求参数。
 *
 * <p>生产实现见阶段 5（高熵 token + HMAC 签名 + 失败指数锁定）；
 * {@link AllowAllClientAuthenticator} 仅用于开发与测试。</p>
 *
 * @author lianghang
 */
public interface ClientAuthenticator {

    AuthResult authenticate(AuthRequest request);
}
