package com.linkora.client;

/** 客户端运行状态。 */
public enum ClientState {

    /** 已构建，尚未启动。 */
    NEW,

    /** 运行中（SSE 连接 + 后台补传/恢复调度已启动）。 */
    RUNNING,

    /** 已关闭，不再发起任何请求。 */
    CLOSED
}
