package com.linkora.server.servlet;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 通用 POST 接入端点适配器：把一次 HTTP POST 翻译为
 * {@link LinkoraHttpHandler} 调用（用于消息上报 /ingest 与 ACK /ack 端点）。
 *
 * <p>Servlet 只负责：请求体有界读取、请求头快照、clientId 提取、结果回写；
 * 认证、协议校验、持久化全部由上层 handler（linkora-server）完成。</p>
 *
 * @author lianghang
 */
public final class LinkoraEndpointServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private final LinkoraHttpHandler handler;
    private final long maxBodyBytes;

    public LinkoraEndpointServlet(LinkoraHttpHandler handler, long maxBodyBytes) {
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("maxBodyBytes 必须为正数");
        }
        this.handler = handler;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String clientId = request.getHeader(SsePushServlet.CLIENT_ID_HEADER);
        byte[] body = readBodyBounded(request, response);
        if (body == null) {
            return; // 已回写 413
        }
        LinkoraHttpRequest snapshot = new LinkoraHttpRequest(
                clientId, request.getMethod(), snapshotHeaders(request), body);
        LinkoraHttpResult result;
        try {
            result = handler.handle(snapshot);
        } catch (RuntimeException e) {
            result = new LinkoraHttpResult(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    String.valueOf(e.getMessage() == null ? "内部错误" : e.getMessage())
                            .getBytes("UTF-8"), "text/plain;charset=UTF-8");
        }
        response.setStatus(result.getStatus());
        if (result.getContentType() != null) {
            response.setContentType(result.getContentType());
        }
        if (result.getBody() != null && result.getBody().length > 0) {
            response.getOutputStream().write(result.getBody());
        }
    }

    /** 有界读取请求体；超限回写 413 并返回 null。 */
    private byte[] readBodyBounded(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ServletInputStream in = request.getInputStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                response.setStatus(413); // SC_PAYLOAD_TOO_LARGE 为 Servlet 4.0 常量，3.1 用字面量
                response.setContentType("text/plain;charset=UTF-8");
                response.getOutputStream().write("请求体超过大小限制".getBytes("UTF-8"));
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static Map<String, String> snapshotHeaders(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
