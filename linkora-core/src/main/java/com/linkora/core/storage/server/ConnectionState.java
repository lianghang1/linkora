package com.linkora.core.storage.server;

/**
 * 客户端连接状态。
 *
 * @author lianghang
 */
public enum ConnectionState {

    /** SSE 连接在线。 */
    CONNECTED,

    /** 连接断开或从未连接。 */
    DISCONNECTED
}
