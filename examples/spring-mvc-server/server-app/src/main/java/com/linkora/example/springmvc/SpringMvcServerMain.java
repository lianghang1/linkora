package com.linkora.example.springmvc;

import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.server.LinkoraServer;
import com.linkora.spring.LinkoraServlets;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.File;

/**
 * Spring MVC 宿主示例：注解驱动容器 + linkora-spring 装配 LinkoraServer + 内嵌 Tomcat 承载。
 *
 * <p>端点布局：</p>
 * <pre>
 *   GET  /api/status          业务状态（Spring MVC）
 *   POST /api/notify          业务下发消息（Spring MVC）
 *   GET  /events              SSE 下行（Linkora）
 *   POST /messages /acks      上行与 ACK（Linkora）
 * </pre>
 *
 * <p>运行方式（仓库根目录，首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/spring-mvc-server/server-app exec:java ^
 *       -Dexec.mainClass=com.linkora.example.springmvc.SpringMvcServerMain ^
 *       -Dexec.args="8081"
 * </pre>
 *
 * <p>生产提示：本示例用内嵌 Tomcat 演示（与 Spring Boot 默认容器同源，行为一致）；
 * 真实 Spring MVC 部署在 WebApplicationInitializer / web.xml 中对 ServletContext
 * 调用 {@link LinkoraServlets#register} 即可，无其他差别。</p>
 *
 * @author lianghang
 */
public final class SpringMvcServerMain {

    private static final Logger log = LoggerFactory.getLogger(SpringMvcServerMain.class);
    private static final String DEMO_CLIENT_ID = "demo-client";

    private SpringMvcServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        Tomcat tomcat = startServer(port);

        System.out.println("========================================");
        System.out.println("Linkora Spring MVC 示例已启动（内嵌 Tomcat）");
        System.out.println("  业务接口 : http://127.0.0.1:" + port + "/api/orders /api/reviews");
        System.out.println("  上行查询 : http://127.0.0.1:" + port + "/api/uplink/order-status-updates");
        System.out.println("  SSE 下行 : http://127.0.0.1:" + port + "/events");
        System.out.println("  消息上报 : http://127.0.0.1:" + port + "/messages");
        System.out.println("  ACK 上报 : http://127.0.0.1:" + port + "/acks");
        System.out.println("========================================");

        Thread.currentThread().join();
    }

    /** 启动完整服务（内嵌 Tomcat + Spring 容器 + Linkora 三端点），返回已启动的 Tomcat 实例。 */
    public static Tomcat startServer(int port) throws Exception {
        // 1. 搭建内嵌 Tomcat，拿到 ServletContext 后再刷新 Spring 容器
        //    （@EnableWebMvc 的 MVC 基础设施在 refresh 期就需要 ServletContext）
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.getConnector(); // 必须显式创建连接器，否则内嵌 Tomcat 不监听端口
        tomcat.setBaseDir(new File("data/tomcat-mvc").getAbsolutePath());
        Context tomcatCtx = tomcat.addContext("", new File(".").getAbsolutePath());

        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(AppConfig.class);
        context.setServletContext(tomcatCtx.getServletContext());
        context.refresh();

        LinkoraServer server = context.getBean(LinkoraServer.class);

        // 2. 注册演示客户端（幂等）
        if (!server.isRunning()) {
            throw new IllegalStateException("LinkoraServer 应由容器启动");
        }
        ClientRegistry registry = context.getBean(ClientRegistry.class);
        if (!registry.findByClientId(DEMO_CLIENT_ID).isPresent()) {
            registry.register(new ClientRegistration(
                    DEMO_CLIENT_ID, true, System.currentTimeMillis()));
        }

        // 3. Spring MVC 分发器 + Linkora 三端点。
        //    注意：Spring 刷新期会触发 Tomcat 上下文初始化，之后 ServletContext.addServlet
        //    （规范 API，含状态检查）会被拒——内嵌场景须用 Tomcat 原生 Wrapper API 注册
        //    （war 包宿主在 WebApplicationInitializer 阶段注册则不受影响，仍用 LinkoraServlets）
        DispatcherServlet dispatcher = new DispatcherServlet(context);
        Tomcat.addServlet(tomcatCtx, "spring-mvc", dispatcher).setLoadOnStartup(1);
        tomcatCtx.addServletMappingDecoded("/*", "spring-mvc");
        addServlet(tomcatCtx, "linkoraSsePushServlet",
                new com.linkora.server.servlet.SsePushServlet(
                        server.sseSubscribeHandler(), server.sseConnectionRegistry(),
                        15_000L, 3_000L, server.getMaxOutboundBytesPerConnection()),
                "/events");
        addServlet(tomcatCtx, "linkoraIngestServlet",
                new com.linkora.server.servlet.LinkoraEndpointServlet(
                        server.ingestHttpHandler(), server.getMaxBodyBytes()),
                "/messages");
        addServlet(tomcatCtx, "linkoraAckServlet",
                new com.linkora.server.servlet.LinkoraEndpointServlet(
                        server.ackHttpHandler(), server.getMaxBodyBytes()),
                "/acks");
        // 可选：嵌入式运维控制台。内嵌 Tomcat 场景（Spring 刷新已触发上下文初始化）
        // 须用 Tomcat 原生 API 注册——与上方三端点同因；war 宿主在初始化期注册
        // 则用 AdminConsoleInstaller（规范 API）。令牌经系统属性注入，未设置不装配。
        String consoleToken = System.getProperty("linkora.admin.console.token");
        if (consoleToken != null && !consoleToken.trim().isEmpty()) {
            Tomcat.addServlet(tomcatCtx, "linkoraAdminConsoleServlet",
                            new com.linkora.adminconsole.AdminConsoleServlet(server))
                    .setAsyncSupported(true);
            tomcatCtx.addServletMappingDecoded("/linkora-admin/*", "linkoraAdminConsoleServlet");
            org.apache.tomcat.util.descriptor.web.FilterDef filterDef =
                    new org.apache.tomcat.util.descriptor.web.FilterDef();
            filterDef.setFilterName("linkoraAdminConsoleFilter");
            filterDef.setFilter(new com.linkora.adminconsole.AdminConsoleFilter(consoleToken.trim()));
            tomcatCtx.addFilterDef(filterDef);
            org.apache.tomcat.util.descriptor.web.FilterMap filterMap =
                    new org.apache.tomcat.util.descriptor.web.FilterMap();
            filterMap.setFilterName("linkoraAdminConsoleFilter");
            filterMap.addURLPattern("/linkora-admin/*");
            tomcatCtx.addFilterMap(filterMap);
        }
        tomcat.start();

        // 优雅关闭：先停容器再关 Spring（容器销毁时 LinkoraServer.close()）
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tomcat.stop();
                } catch (Exception ignore) {
                    // 关闭阶段异常不影响退出
                }
                context.close();
                System.out.println("Linkora Spring MVC 示例已关闭");
            }
        }, "spring-mvc-demo-shutdown"));
        return tomcat;
    }

    /** Tomcat 原生注册（asyncSupported 必须显式开，SSE 依赖异步）。 */
    private static void addServlet(Context ctx, String name,
                                   javax.servlet.Servlet servlet, String mapping) {
        org.apache.catalina.Wrapper wrapper = Tomcat.addServlet(ctx, name, servlet);
        wrapper.setAsyncSupported(true);
        ctx.addServletMappingDecoded(mapping, name);
    }
}
