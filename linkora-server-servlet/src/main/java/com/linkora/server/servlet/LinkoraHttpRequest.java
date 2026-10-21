package com.linkora.server.servlet;

import java.util.Collections;
import java.util.Map;

/** 端点请求快照（由 {@link LinkoraEndpointServlet} 组装）。 */
public final class LinkoraHttpRequest {

    private final String clientId;
    private final String method;
    private final Map<String, String> headers;
    private final byte[] body;

    public LinkoraHttpRequest(String clientId, String method,
                              Map<String, String> headers, byte[] body) {
        this.clientId = clientId;
        this.method = method;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(headers);
        this.body = body == null ? new byte[0] : body.clone();
    }

    /** 可为 null（缺失 X-Linkora-Client-Id 头时）。 */
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
