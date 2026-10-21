package com.linkora.spring;

import com.linkora.server.LinkoraServer;
import com.linkora.server.servlet.LinkoraEndpointServlet;
import com.linkora.server.servlet.SsePushServlet;

import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;

/**
 * 把 {@link LinkoraServer} 的三个端点注册进任意 ServletContext 的工具。
 *
 * <p>适用于 Spring MVC（非 Boot）宿主：在 WebApplicationInitializer 或
 * 内嵌 Jetty/Tomcat 装配代码中，拿到 {@link LinkoraServer} bean 后调用
 * {@link #register(LinkoraServer, ServletContext, String)}。</p>
 *
 * <p>注册内容（与客户端默认路径约定一致）：</p>
 * <pre>
 *   GET  {prefix}/events    SSE 下行（SsePushServlet）
 *   POST {prefix}/messages  消息上报（LinkoraEndpointServlet）
 *   POST {prefix}/acks      ACK 上报（LinkoraEndpointServlet）
 * </pre>
 *
 * @author lianghang
 */
public final class LinkoraServlets {

    private LinkoraServlets() {
    }

    /** 以根路径前缀注册三个端点。 */
    public static void register(LinkoraServer server, ServletContext servletContext) {
        register(server, servletContext, "");
    }

    /**
     * 注册三个端点 Servlet。
     *
     * @param pathPrefix 上下文内路径前缀，如 "" 或 "/linkora"；必须以 / 开头或为空
     */
    public static void register(LinkoraServer server, ServletContext servletContext, String pathPrefix) {
        if (server == null || servletContext == null) {
            throw new IllegalArgumentException("server 与 servletContext 不能为 null");
        }
        String prefix = normalize(pathPrefix);
        add(servletContext, "linkoraSsePushServlet",
                new SsePushServlet(server.sseSubscribeHandler(), server.sseConnectionRegistry()),
                prefix + "/events");
        add(servletContext, "linkoraIngestServlet",
                new LinkoraEndpointServlet(server.ingestHttpHandler()),
                prefix + "/messages");
        add(servletContext, "linkoraAckServlet",
                new LinkoraEndpointServlet(server.ackHttpHandler()),
                prefix + "/acks");
    }

    private static void add(ServletContext servletContext, String name, javax.servlet.Servlet servlet,
                            String mapping) {
        ServletRegistration.Dynamic registration = servletContext.addServlet(name, servlet);
        if (registration == null) {
            throw new IllegalStateException("Servlet 注册失败（名称冲突）: " + name);
        }
        registration.setAsyncSupported(true);
        registration.addMapping(mapping);
    }

    private static String normalize(String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isEmpty()) {
            return "";
        }
        if (!pathPrefix.startsWith("/")) {
            throw new IllegalArgumentException("pathPrefix 必须以 / 开头或为空: " + pathPrefix);
        }
        return pathPrefix.endsWith("/") ? pathPrefix.substring(0, pathPrefix.length() - 1) : pathPrefix;
    }
}
