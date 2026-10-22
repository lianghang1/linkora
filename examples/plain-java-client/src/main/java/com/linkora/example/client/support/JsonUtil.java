package com.linkora.example.client.support;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 示例共享 JSON 工具：所有业务消息 payload 统一使用 JSON（UTF-8），
 * 替代 "|" 拼接文本，字段结构演进无需改拆分逻辑。
 * {@link ObjectMapper} 线程安全，配置完成后只读共享。
 *
 * @author lianghang
 */
public final class JsonUtil {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtil() {
    }
}
