package com.linkora.client.auth;

/**
 * 客户端 token 动态提供 SPI（0.6.0）：宿主已有账号密码登录体系时实现本接口，
 * SDK 每次出站请求前回调获取当前有效 token（宿主可在内部完成续期/刷新，
 * SDK 不感知过期细节，失败自动随 at-least-once 重试）。
 *
 * <p>与静态配置 {@code linkora.client.auth-token} 互斥：配置了 TokenProvider
 * Bean 时优先于静态 token。</p>
 *
 * <p>实现要求：线程安全（IO 与 SSE 线程并发调用）；返回 null 视为匿名请求
 * （将被服务端拒绝并重试）。</p>
 *
 * @author lianghang
 */
public interface TokenProvider {

    /** 返回当前有效 token（如宿主登录体系下发的 JWT/会话 token）。 */
    String currentToken();
}
