package com.linkora.server.servlet;

/** 端点处理结果（状态码 + 响应体）。 */
public final class LinkoraHttpResult {

    private final int status;
    private final byte[] body;
    private final String contentType;

    public LinkoraHttpResult(int status, byte[] body, String contentType) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
    }

    public int getStatus() {
        return status;
    }

    public byte[] getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }
}
