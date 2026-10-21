package com.linkora.core.error;

/**
 * Linkora 统一错误码。对外稳定，新增只能追加不能修改语义。
 */
public enum LinkoraErrorCode {

    /** 未提供认证凭据。 */
    AUTH_REQUIRED("AUTH_REQUIRED"),

    /** 认证失败（凭据无效或签名校验不通过）。 */
    AUTH_FAILED("AUTH_FAILED"),

    /** 已认证但无权访问目标资源（如 clientId 不匹配）。 */
    FORBIDDEN("FORBIDDEN"),

    /** 消息结构、字段或协议版本校验失败。 */
    INVALID_MESSAGE("INVALID_MESSAGE"),

    /** 消息体超过大小限制。 */
    MESSAGE_TOO_LARGE("MESSAGE_TOO_LARGE"),

    /** 同一 messageId 的消息已存在（幂等去重命中）。 */
    DUPLICATE_MESSAGE("DUPLICATE_MESSAGE"),

    /** 触发速率限制或连接数限制。 */
    RATE_LIMITED("RATE_LIMITED"),

    /** 存储层暂时不可用或读写失败。 */
    STORAGE_ERROR("STORAGE_ERROR"),

    /** 负载序列化/反序列化失败。 */
    SERIALIZATION_ERROR("SERIALIZATION_ERROR"),

    /** 重试次数耗尽（消息进入可恢复失败或死信，不删除）。 */
    RETRY_EXHAUSTED("RETRY_EXHAUSTED"),

    /** 协议版本不受支持。 */
    UNSUPPORTED_PROTOCOL("UNSUPPORTED_PROTOCOL"),

    /** 未分类的内部错误。 */
    INTERNAL_ERROR("INTERNAL_ERROR");

    private final String code;

    LinkoraErrorCode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
