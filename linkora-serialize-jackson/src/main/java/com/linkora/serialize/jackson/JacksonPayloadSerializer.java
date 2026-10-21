package com.linkora.serialize.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.serialize.PayloadSerializer;

/**
 * 基于 Jackson databind 的默认 {@link PayloadSerializer} 实现。
 *
 * <p>仅使用 Jackson 2.13+ 的稳定 API，避免与宿主环境的 Jackson 版本仲裁冲突。
 * 实例线程安全（{@link ObjectMapper} 配置完成后不可变）。</p>
 *
 * @author lianghang
 */
public final class JacksonPayloadSerializer implements PayloadSerializer {

    private final ObjectMapper mapper;

    public JacksonPayloadSerializer() {
        this(new ObjectMapper());
    }

    public JacksonPayloadSerializer(ObjectMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("ObjectMapper 不能为 null");
        }
        this.mapper = mapper;
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public byte[] serialize(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为 null");
        }
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "负载序列化失败: " + payload.getClass().getName(), e);
        }
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null || type == null) {
            throw new IllegalArgumentException("data 与 type 不能为 null");
        }
        try {
            return mapper.readValue(data, type);
        } catch (Exception e) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "负载反序列化失败: " + type.getName(), e);
        }
    }
}
