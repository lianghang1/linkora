package com.linkora.server;

/**
 * 发布结果：表示服务端已持久化接受（区分于客户端已接收/处理）。
 * duplicate=true 表示同一 messageId 已存在（幂等命中），不重复分配投递序号。
 */
public final class PublishResult {

    private final String messageId;
    private final long seq;
    private final boolean duplicate;

    public PublishResult(String messageId, long seq, boolean duplicate) {
        this.messageId = messageId;
        this.seq = seq;
        this.duplicate = duplicate;
    }

    public String getMessageId() {
        return messageId;
    }

    /** 目标客户端的投递序号；duplicate=true 时为 -1。 */
    public long getSeq() {
        return seq;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    @Override
    public String toString() {
        return "PublishResult{messageId='" + messageId + "', seq=" + seq + ", duplicate=" + duplicate + '}';
    }
}
