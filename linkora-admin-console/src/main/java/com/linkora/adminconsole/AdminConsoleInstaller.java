package com.linkora.adminconsole;

import com.linkora.server.LinkoraServer;

import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import java.util.EnumSet;

/**
 * 控制台安装器：供 MVC / 纯 Servlet 宿主在 {@link ServletContext} 上手动装配。
 *
 * <p>Spring Boot 宿主无需使用本类，直接引入
 * {@code linkora-admin-console} 依赖并配置
 * {@code linkora.admin-console.enabled=true} + {@code token}，
 * 由 starter 自动装配（默认关闭）。</p>
 *
 * <pre>{@code
 * // ServletContextListener#contextInitialized 中：
 * AdminConsoleInstaller.register(ctx, linkoraServer, "your-token");
 * // 控制台位于 /linkora-admin/
 * }</pre>
 *
 * @author lianghang
 */
public final class AdminConsoleInstaller {

    public static final String DEFAULT_PATH = "/linkora-admin";

    private AdminConsoleInstaller() {
    }

    /** 以默认路径 {@value #DEFAULT_PATH} 装配控制台。 */
    public static void register(ServletContext ctx, LinkoraServer server, String token) {
        register(ctx, server, token, DEFAULT_PATH);
    }

    /**
     * 在指定路径装配控制台（页面与端点位于 {@code path/*}）。
     *
     * @param token 控制台访问令牌，必填（安全默认：不给令牌宁可装配失败）
     */
    public static void register(ServletContext ctx, LinkoraServer server,
                                String token, String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            throw new IllegalArgumentException("path 不能为空或根路径");
        }
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("控制台令牌不能为空（linkora.admin-console.token）");
        }
        ctx.addServlet("linkoraAdminConsoleServlet", new AdminConsoleServlet(server))
                .addMapping(path + "/*");
        ctx.addFilter("linkoraAdminConsoleFilter", new AdminConsoleFilter(token))
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, path + "/*");
    }
}
