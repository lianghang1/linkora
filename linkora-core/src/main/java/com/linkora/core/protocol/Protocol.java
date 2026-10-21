package com.linkora.core.protocol;

/**
 * 线路协议常量。协议版本号写入所有请求/事件；
 * 服务端拒绝不支持的版本并返回 UNSUPPORTED_PROTOCOL。
 * 兼容策略：主版本不兼容，次版本向后兼容。
 *
 * @author lianghang
 */
public final class Protocol {

    /** 当前线路协议版本。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 协议版本请求头。 */
    public static final String PROTOCOL_VERSION_HEADER = "X-Linkora-Protocol";

    private Protocol() {
    }
}
