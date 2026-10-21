package com.linkora.server.auth;

import java.util.Collections;
import java.util.Map;

/**
 * 认证请求快照：携带客户端声明身份、HTTP 方法、请求头与请求体，
 * 供认证器实现应用层验证（如 token + HMAC 签名）。
 *
 * @author lianghang
 */
public final class AuthRequest {

    private final String clientId;
    private final String method;
    private final Map<String, String> headers;
    private final byte[] body;

    public AuthRequest(String clientId, String method, Map<String, String> headers, byte[] body) {
        this.clientId = clientId;
        this.method = method;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(headers);
        this.body = body == null ? new byte[0] : body.clone();
    }

    /** 客户端声明标识；可能为 null（请求未携带）。 */
    public String getClientId() {
        return clientId;
    }

    public String getMethod() {
        return method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /** 大小写不敏感读取第一个头值；不存在返回 null。 */
    public String header(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public byte[] getBody() {
        return body.clone();
    }

    public int getBodySize() {
        return body.length;
    }
}
