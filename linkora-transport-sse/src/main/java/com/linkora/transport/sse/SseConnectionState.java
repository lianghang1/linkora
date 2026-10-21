package com.linkora.transport.sse;

/**
 * SSE 长连接状态。CLOSED 仅表示手动关闭后的终态；
 * 断线后的自动重连期间处于 CONNECTING。
 *
 * @author lianghang
 */
public enum SseConnectionState {

    /** 正在建立连接（含等待重连退避）。 */
    CONNECTING,

    /** 连接已建立，正在接收事件。 */
    OPEN,

    /** 已手动关闭，不再重连。 */
    CLOSED
}
