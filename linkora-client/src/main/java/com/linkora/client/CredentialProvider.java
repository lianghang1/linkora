package com.linkora.client;

import java.util.Map;

/**
 * 客户端凭据 SPI：为每个出站请求（POST 上报 / ACK / SSE 订阅）提供认证头。
 * 阶段 5 的 HMAC 签名实现基于 method + body 计算签名。
 *
 * <p>实现必须线程安全（会被 IO 与 SSE 线程并发调用）。</p>
 *
 * @author lianghang
 */
public interface CredentialProvider {

    /**
     * 返回本次请求需要附加的认证头（不含 X-Linkora-Client-Id 与协议版本头，
     * 这两个由客户端统一附加）。可为空 Map 表示匿名。
     */
    Map<String, String> authHeaders(RequestMaterial material);

    /** 请求材料（用于签名计算）。 */
    final class RequestMaterial {

        private final String method;
        private final byte[] body;

        public RequestMaterial(String method, byte[] body) {
            this.method = method;
            this.body = body == null ? new byte[0] : body.clone();
        }

        public String getMethod() {
            return method;
        }

        public byte[] getBody() {
            return body.clone();
        }
    }
}
