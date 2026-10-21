package com.linkora.transport.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变的传输响应。body 已被完整读入内存（受大小上限保护）。
 *
 * @author lianghang
 */
public final class TransportResponse {

    private final int code;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    TransportResponse(int code, Map<String, List<String>> headers, byte[] body) {
        this.code = code;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, List<String>>(headers));
        this.body = body == null ? new byte[0] : body;
    }

    public int getCode() {
        return code;
    }

    /** 是否为 2xx 成功响应。 */
    public boolean isSuccessful() {
        return code >= 200 && code < 300;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public byte[] getBody() {
        return body.clone();
    }

    public int getBodySize() {
        return body.length;
    }

    /** 返回指定响应头第一个值，不存在时返回 null（大小写不敏感）。 */
    public String header(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }
}
