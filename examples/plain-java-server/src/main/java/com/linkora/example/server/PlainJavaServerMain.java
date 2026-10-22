package com.linkora.example.server;

import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.example.server.controller.PlatformController;
import com.linkora.example.server.service.OrderService;
import com.linkora.example.server.service.ReviewService;
import com.linkora.example.server.service.uplink.OrderStatusHandler;
import com.linkora.example.server.service.uplink.ReviewReplyHandler;
import com.linkora.example.server.service.uplink.UplinkMessageDispatcher;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.servlet.LinkoraEndpointServlet;
import com.linkora.server.servlet.SsePushServlet;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.h2.jdbcx.JdbcDataSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * 普通 Java 服务端示例（线上平台侧）：演示"线上平台 ⇄ 线下门店"双向业务消息。
 * 分层结构：controller 包暴露业务 HTTP 接口，service 包封装下行发布，
 * service.uplink 包处理上行消息（经框架 {@code builder.ingestListener} 手动接入）。
 *
 * <p>演示闭环（对端为 plain-java-client 示例）：</p>
 * <ol>
 *   <li>下行：POST /api/orders、/api/reviews → 门店端两个 handler 收到并 ACK；</li>
 *   <li>上行：门店端上报 order-status-updated、review-reply → 本端两个
 *       UplinkHandler 处理（GET /api/uplink/* 查询）。</li>
 * </ol>
 *
 * <p>适用场景与限制：本示例用嵌入式 Jetty 演示完整链路；生产环境请将
 * {@link SsePushServlet} / {@link LinkoraEndpointServlet} 注册进宿主已有
 * Servlet 3.1+ 容器，数据库换成业务库（MySQL/PostgreSQL 等，JDBC 兼容），
 * 并将 {@link AllowAllClientAuthenticator} 替换为
 * {@code HmacClientAuthenticator}（见 docs/security.md）。</p>
 *
 * <p>运行方式（仓库根目录，首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/plain-java-server compile exec:java ^
 *       -Dexec.mainClass=com.linkora.example.server.PlainJavaServerMain ^
 *       -Dexec.args="8080 data/linkora-server"
 * </pre>
 *
 * @author lianghang
 */
public final class PlainJavaServerMain {

    private static final String DEMO_CLIENT_ID = "demo-client";

    private PlainJavaServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path dbPath = Paths.get(args.length > 1 ? args[1] : "data/linkora-server")
                .toAbsolutePath();

        // 1. 服务端持久化：H2 文件库（生产换业务库，schema 自动迁移）
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:file:" + dbPath + ";AUTO_SERVER=FALSE");
        JdbcServerSchema.migrate(dataSource);

        // 2. 注册演示客户端（幂等：重复启动不重复注册）
        JdbcClientRegistry registry = new JdbcClientRegistry(dataSource);
        if (!registry.findByClientId(DEMO_CLIENT_ID).isPresent()) {
            registry.register(new ClientRegistration(
                    DEMO_CLIENT_ID, true, System.currentTimeMillis()));
        }

        // 3. 业务分层装配：上行处理器 → 分发器（框架 IngestListener 唯一接入点）
        OrderStatusHandler orderStatusHandler = new OrderStatusHandler();
        ReviewReplyHandler reviewReplyHandler = new ReviewReplyHandler();
        UplinkMessageDispatcher dispatcher = new UplinkMessageDispatcher(
                Arrays.asList(orderStatusHandler, reviewReplyHandler));

        // 4. 组装 LinkoraServer（此处用放行认证器便于演示；生产用 HmacClientAuthenticator）
        LinkoraServer server = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(registry)
                .messageRepository(new JdbcMessageRepository(dataSource))
                .deliveryRepository(new JdbcDeliveryRepository(dataSource))
                .cursorRepository(new JdbcClientCursorRepository(dataSource))
                .config(ServerConfig.builder().build())
                .ingestListener(dispatcher)
                .build();
        server.start();

        // 5. 挂载端点：框架三端点（SSE 下行 / 消息上报 / ACK 上报）+ 业务 controller
        Server jetty = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        jetty.setHandler(context);
        context.addServlet(new ServletHolder(
                new SsePushServlet(server.sseSubscribeHandler(), server.sseConnectionRegistry())), "/events");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ingestHttpHandler())), "/messages");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ackHttpHandler())), "/acks");
        context.addServlet(new ServletHolder(new PlatformController(
                new OrderService(server), new ReviewService(server),
                orderStatusHandler, reviewReplyHandler)), "/api/*");
        jetty.start();

        System.out.println("========================================");
        System.out.println("Linkora 演示服务端已启动（线上平台侧）");
        System.out.println("  SSE 下行   : http://127.0.0.1:" + port + "/events");
        System.out.println("  消息上报   : http://127.0.0.1:" + port + "/messages");
        System.out.println("  ACK 上报   : http://127.0.0.1:" + port + "/acks");
        System.out.println("  线上下单   : POST http://127.0.0.1:" + port + "/api/orders");
        System.out.println("  线上评价   : POST http://127.0.0.1:" + port + "/api/reviews");
        System.out.println("  查上行状态 : GET  http://127.0.0.1:" + port + "/api/uplink/order-status-updates");
        System.out.println("  查上行回复 : GET  http://127.0.0.1:" + port + "/api/uplink/review-replies");
        System.out.println("  演示客户端 : " + DEMO_CLIENT_ID);
        System.out.println("  数据文件   : " + dbPath + ".mv.db");
        System.out.println("========================================");

        // 6. Ctrl+C 优雅关闭（先停容器再停编排，保证在途请求处理完）
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    jetty.stop();
                } catch (Exception ignore) {
                    // 关闭阶段异常不影响退出
                }
                server.close();
                System.out.println("Linkora 演示服务端已关闭");
            }
        }, "linkora-demo-shutdown"));

        Thread.currentThread().join();
    }
}
