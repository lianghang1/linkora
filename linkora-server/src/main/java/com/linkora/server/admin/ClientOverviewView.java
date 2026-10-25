package com.linkora.server.admin;

/**
 * 客户端概览的管理视图（人类可读）：一眼看清谁在线、谁压着消息、谁有死信。
 *
 * @author lianghang
 */
public final class ClientOverviewView {

    private final String clientId;
    private final boolean enabled;
    private final boolean online;
    private final int connectionCount;
    private final int unfinishedCount;
    private final long deadLetterCount;
    private final String createdAt;
    private final String displayName;

    public ClientOverviewView(String clientId, String displayName, boolean enabled, int connectionCount,
                              int unfinishedCount, long deadLetterCount, long createdAt) {
        this.clientId = clientId;
        this.enabled = enabled;
        this.online = connectionCount > 0;
        this.connectionCount = connectionCount;
        this.unfinishedCount = unfinishedCount;
        this.deadLetterCount = deadLetterCount;
        this.createdAt = AdminViews.formatTime(createdAt);
        this.displayName = displayName == null || displayName.trim().isEmpty() ? null : displayName.trim();
    }

    public String getClientId() {
        return clientId;
    }

    /** 是否启用（禁用的客户端拒收新消息、拒绝接入）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 当前是否有活跃 SSE 连接。 */
    public boolean isOnline() {
        return online;
    }

    /** 当前活跃 SSE 连接数。 */
    public int getConnectionCount() {
        return connectionCount;
    }

    /** 未终结投递数（含重试中与死信）。 */
    public int getUnfinishedCount() {
        return unfinishedCount;
    }

    /** 死信数量（需人工介入）。 */
    public long getDeadLetterCount() {
        return deadLetterCount;
    }

    /** 注册时间（已格式化）。 */
    public String getCreatedAt() {
        return createdAt;
    }

    /** 可为 null（人类可读名称，显示时回退到 clientId）。 */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return "ClientOverviewView{clientId='" + clientId + "', online=" + online
                + ", unfinished=" + unfinishedCount + ", deadLetters=" + deadLetterCount + "}";
    }
}
