package com.linkora.core.security;

/**
 * 应用层安全请求头常量。全部头名与取值不区分大小写匹配（HTTP 语义）。
 */
public final class SecurityHeaders {

    private SecurityHeaders() {
    }

    /** 客户端访问令牌（服务端发放的高熵随机值，建议 ≥128 bit）。 */
    public static final String ACCESS_TOKEN = "X-Linkora-Access-Token";

    /** 请求发起时间戳（epoch 毫秒），服务端校验时钟窗口。 */
    public static final String TIMESTAMP = "X-Linkora-Timestamp";

    /** 一次性随机数（hex），配合时间戳防重放。 */
    public static final String NONCE = "X-Linkora-Nonce";

    /** HMAC-SHA256 请求签名（hex 小写）。 */
    public static final String SIGNATURE = "X-Linkora-Signature";
}
