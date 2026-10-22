package com.linkora.transport.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 不可变的传输请求。上层组装 URL、鉴权头与消息体；
 * 各超时字段为 null 时使用 {@link OkHttpTransport} 的全局默认值。
 *
 * @author lianghang
 */
public final class TransportRequest {

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final byte[] body;
    private final Long connectTimeoutMs;
    private final Long readTimeoutMs;
    private final Long writeTimeoutMs;
    private final Long callTimeoutMs;

    private TransportRequest(Builder builder) {
        if (builder.url == null || builder.url.trim().isEmpty()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        this.url = builder.url;
        this.method = builder.method == null ? "POST" : builder.method;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.headers));
        this.body = builder.body == null ? null : builder.body.clone();
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
        this.writeTimeoutMs = builder.writeTimeoutMs;
        this.callTimeoutMs = builder.callTimeoutMs;
    }

    public static Builder newBuilder(String url) {
        return new Builder(url);
    }

    public String getUrl() {
        return url;
    }

    public String getMethod() {
        return method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    /** 可为 null 表示无请求体。返回内部数组的防御性拷贝。 */
    public byte[] getBody() {
        return body == null ? null : body.clone();
    }

    public Long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public Long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public Long getWriteTimeoutMs() {
        return writeTimeoutMs;
    }

    public Long getCallTimeoutMs() {
        return callTimeoutMs;
    }

    public static final class Builder {
        private final String url;
        private String method = "POST";
        private Map<String, String> headers = new LinkedHashMap<String, String>();
        private byte[] body;
        private Long connectTimeoutMs;
        private Long readTimeoutMs;
        private Long writeTimeoutMs;
        private Long callTimeoutMs;

        private Builder(String url) {
            this.url = url;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder header(String name, String value) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("header 名不能为空");
            }
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }

        public Builder body(byte[] body) {
            this.body = body;
            return this;
        }

        public Builder connectTimeoutMs(Long timeoutMs) {
            this.connectTimeoutMs = timeoutMs;
            return this;
        }

        public Builder readTimeoutMs(Long timeoutMs) {
            this.readTimeoutMs = timeoutMs;
            return this;
        }

        public Builder writeTimeoutMs(Long timeoutMs) {
            this.writeTimeoutMs = timeoutMs;
            return this;
        }

        public Builder callTimeoutMs(Long timeoutMs) {
            this.callTimeoutMs = timeoutMs;
            return this;
        }

        public TransportRequest build() {
            return new TransportRequest(this);
        }
    }
}
