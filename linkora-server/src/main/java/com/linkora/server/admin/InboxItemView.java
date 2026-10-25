package com.linkora.server.admin;

/**
 * 收件箱条目（人类可读）：需要人关注的事项。
 *
 * <p>type = dead（死信，自动机制用尽需人工决策）或
 * stuck（长时间无推进，自愈机制疑似失效）。</p>
 */
public final class InboxItemView {

    private final String type;
    private final String clientId;
    private final String messageId;
    private final String subject;
    private final String messageType;
    private final String state;
    private final String stateText;
    private final int retryCount;
    private final String lastError;
    private final String updatedAt;
    /** 距最近推进/产生至今的人话时长（如「40 分钟」）。 */
    private final String ageText;

    public InboxItemView(String type, String clientId, String messageId, String subject,
                         String messageType, String state, String stateText,
                         int retryCount, String lastError, String updatedAt, String ageText) {
        this.type = type;
        this.clientId = clientId;
        this.messageId = messageId;
        this.subject = subject;
        this.messageType = messageType;
        this.state = state;
        this.stateText = stateText;
        this.retryCount = retryCount;
        this.lastError = lastError;
        this.updatedAt = updatedAt;
        this.ageText = ageText;
    }

    public String getType() { return type; }
    public String getClientId() { return clientId; }
    public String getMessageId() { return messageId; }
    public String getSubject() { return subject; }
    public String getMessageType() { return messageType; }
    public String getState() { return state; }
    public String getStateText() { return stateText; }
    public int getRetryCount() { return retryCount; }
    public String getLastError() { return lastError; }
    public String getUpdatedAt() { return updatedAt; }
    public String getAgeText() { return ageText; }
}
