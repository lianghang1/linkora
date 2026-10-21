package com.linkora.core.serialize;

/**
 * 负载序列化抽象。核心模块不绑定具体 JSON 实现，
 * 默认 JSON 序列化器由上层模块提供（Jackson）。
 * 实现必须线程安全。
 */
public interface PayloadSerializer {

    /** 序列化结果的内容类型标识，如 application/json。 */
    String contentType();

    /** 将业务负载对象序列化为字节。 */
    byte[] serialize(Object payload);

    /** 将字节反序列化为指定类型。 */
    <T> T deserialize(byte[] data, Class<T> type);
}
