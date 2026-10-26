package com.linkora.server.auth;

/**
 * Bearer Token 校验 SPI（0.6.0）：宿主已有账号密码登录体系时实现本接口，
 * 把 linkora 的认证委托给宿主已有体系（按 clientId + token 校验）。
 *
 * <p>与认证方式解耦：HMAC（默认）、Token 回调、匿名三种模式互斥配置；
 * 本 SPI 供 {@code linkora.server.auth-mode=token} 时由 SDK 调用。</p>
 *
 * <p>实现要求：线程安全（IO 线程并发调用）；校验失败返回 false 即可，
 * SDK 统一转为 AUTH_FAILED 拒绝（错误细节建议宿主自行留痕）。</p>
 *
 * @author lianghang
 */
public interface TokenValidator {

    /**
     * 校验 token 是否为宿主账号体系签发的有效凭证。
     *
     * @param clientId 请求声明的客户端标识（SSE 订阅/上行/ACK 都会携带）
     * @param token    Authorization: Bearer 后的 token 原文
     * @return true = 校验通过
     */
    boolean validate(String clientId, String token);
}
