package com.linkora.server.auth;

import com.linkora.core.error.LinkoraErrorCode;

/** 认证结果。失败必须携带稳定错误码与可诊断信息（不泄露凭据细节）。 */
public final class AuthResult {

    private final boolean authenticated;
    private final LinkoraErrorCode errorCode;
    private final String message;

    private AuthResult(boolean authenticated, LinkoraErrorCode errorCode, String message) {
        this.authenticated = authenticated;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static AuthResult success() {
        return new AuthResult(true, null, null);
    }

    public static AuthResult failure(LinkoraErrorCode errorCode, String message) {
        if (errorCode == null) {
            throw new IllegalArgumentException("失败结果的 errorCode 不能为 null");
        }
        return new AuthResult(false, errorCode, message);
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public LinkoraErrorCode getErrorCode() {
        return errorCode;
    }

    public String getMessage() {
        return message;
    }
}
