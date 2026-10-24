package com.linkora.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运维控制台配置项（{@code linkora.admin-console.*}）。
 *
 * <p>默认 {@code enabled=false}：不配置即完全不装配，对宿主零影响。
 * 开启时必须提供 {@code token}（Bearer Token 鉴权，独立于设备 HMAC 体系），
 * 缺失将在装配期快速失败，避免无鉴权端点暴露。</p>
 *
 * @author lianghang
 */
@ConfigurationProperties(prefix = "linkora.admin-console")
public class LinkoraAdminConsoleProperties {

    /** 是否启用控制台（默认 false = 完全不装配）。 */
    private boolean enabled = false;

    /** 控制台访问令牌（Authorization: Bearer <token>），启用时必填。 */
    private String token;

    /** 控制台挂载路径前缀，页面与端点位于 {path}/*。 */
    private String path = "/linkora-admin";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
