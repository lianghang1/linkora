package com.linkora.example.springmvc;

import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.example.springmvc.service.NotifyService;
import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import com.linkora.spring.LinkoraServlets;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Spring MVC 宿主示例：注解驱动容器 + linkora-spring 装配 LinkoraServer + Jetty 承载。
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
 * <p>生产提示：本示例用内嵌 Jetty 演示；真实 Spring MVC 部署在
 * WebApplicationInitializer / web.xml 中对 ServletContext 调用
 * {@link LinkoraServlets#register} 即可，无其他差别。</p>
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

        // 1. 先搭 Jetty 容器结构，拿到 ServletContext 后再刷新 Spring 容器
        //    （@EnableWebMvc 的 MVC 基础设施在 refresh 期就需要 ServletContext）
        Server jetty = new Server(port);
        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        handler.setContextPath("/");
        jetty.setHandler(handler);

        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(AppConfig.class);
        context.setServletContext(handler.getServletContext());
        context.refresh();

        LinkoraServer server = context.getBean(LinkoraServer.class);

        // 2. 注册演示客户端（幂等）并下发欢迎消息
        if (!server.isRunning()) {
            throw new IllegalStateException("LinkoraServer 应由容器启动");
        }
        ClientRegistry registry = context.getBean(ClientRegistry.class);
        if (!registry.findByClientId(DEMO_CLIENT_ID).isPresent()) {
            registry.register(new ClientRegistration(
                    DEMO_CLIENT_ID, true, System.currentTimeMillis()));
        }

        // 3. Spring MVC 分发器 + Linkora 三端点（LinkoraServlets 统一注册）
        ServletHolder dispatcher = new ServletHolder("spring-mvc",
                new DispatcherServlet(context));
        dispatcher.setInitOrder(1);
        handler.addServlet(dispatcher, "/");
        LinkoraServlets.register(server, handler.getServletContext());
        jetty.start();

        PublishResult welcome = context.getBean(NotifyService.class)
                .sendGreeting(DEMO_CLIENT_ID, "欢迎接入 Linkora（Spring MVC 宿主）！");
        log.info("已下发欢迎消息: {}", welcome.getMessageId());

        System.out.println("========================================");
        System.out.println("Linkora Spring MVC 示例已启动");
        System.out.println("  业务接口 : http://127.0.0.1:" + port + "/api/status");
        System.out.println("  SSE 下行 : http://127.0.0.1:" + port + "/events");
        System.out.println("  消息上报 : http://127.0.0.1:" + port + "/messages");
        System.out.println("  ACK 上报 : http://127.0.0.1:" + port + "/acks");
        System.out.println("========================================");

        // 4. 优雅关闭：先停容器再关 Spring（容器销毁时 LinkoraServer.close()）
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    jetty.stop();
                } catch (Exception ignore) {
                    // 关闭阶段异常不影响退出
                }
                context.close();
                System.out.println("Linkora Spring MVC 示例已关闭");
            }
        }, "spring-mvc-demo-shutdown"));

        Thread.currentThread().join();
    }
}
