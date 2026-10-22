package com.linkora.serialize.jackson;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.serialize.PayloadSerializer;

/**
 * 基于 Jackson databind 的默认 {@link PayloadSerializer} 实现。
 *
 * <p>复用 {@link LinkoraJsonUtil} 的共享单例 ObjectMapper（统一配置：时区、
 * 日期格式、容错特性），SDK 与宿主业务共用同一套 JSON 语义；实例无状态、线程安全。</p>
 *
 * @author lianghang
 */
public final class JacksonPayloadSerializer implements PayloadSerializer {

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public byte[] serialize(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为 null");
        }
        return LinkoraJsonUtil.toJsonBytes(payload);
    }

    @Override
    public <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null || type == null) {
            throw new IllegalArgumentException("data 与 type 不能为 null");
        }
        try {
            return LinkoraJsonUtil.getObjectMapper().readValue(data, type);
        } catch (Exception e) {
            throw new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "负载反序列化失败: " + type.getName(), e);
        }
    }
}
