package com.linkora.server;

/** 客户端积压查询结果。
 *
 * @author lianghang
 */
public final class BacklogInfo {

    private final String clientId;
    private final long unfinishedCount;
    private final boolean connected;

    public BacklogInfo(String clientId, long unfinishedCount, boolean connected) {
        this.clientId = clientId;
        this.unfinishedCount = unfinishedCount;
        this.connected = connected;
    }

    public String getClientId() {
        return clientId;
    }

    /** 未完成（未到终态）的投递数量。 */
    public long getUnfinishedCount() {
        return unfinishedCount;
    }

    /** 当前是否至少有一条 SSE 活动连接。 */
    public boolean isConnected() {
        return connected;
    }
}
