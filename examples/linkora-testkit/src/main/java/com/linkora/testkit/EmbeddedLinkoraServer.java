package com.linkora.testkit;

import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.servlet.LinkoraEndpointServlet;
import com.linkora.server.servlet.SsePushServlet;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import com.linkora.core.storage.server.ClientRegistration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.h2.jdbcx.JdbcDataSource;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * 嵌入式 Linkora 服务器（testkit）：H2 内存库 + Jetty + 完整 LinkoraServer 编排，
 * 供客户端集成测试与示例验证使用。生产部署请走宿主 Servlet 容器 + 业务数据库，
 * 本类仅为测试便利存在。
 *
 * <p>端点布局（与客户端默认路径约定一致）：</p>
 * <pre>
 *   GET  {baseUrl}/events    SSE 下行
 *   POST {baseUrl}/messages  消息上报
 *   POST {baseUrl}/acks      ACK 上报
 * </pre>
 *
 * <p>典型用法：</p>
 * <pre>
 *   EmbeddedLinkoraServer server = EmbeddedLinkoraServer.builder()
 *           .registerClient("store-001")
 *           .build();
 *   server.start();
 *   try {
 *       LinkoraClient client = LinkoraClient.builder()
 *               .serverUrl(server.baseUrl())
 *               .clientId("store-001")
 *               .clientStore(store)
 *               .build();
 *       ...
 *   } finally {
 *       server.close();
 *   }
 * </pre>
 *
 * @author lianghang
 */
public final class EmbeddedLinkoraServer implements AutoCloseable {

    private final ClientAuthenticator authenticator;
    private final ServerConfig config;
    private final List<ClientRegistration> pendingRegistrations;

    private JdbcDataSource dataSource;
    private LinkoraServer server;
    private Server jetty;
    private int port = -1;
    private boolean started;

    private EmbeddedLinkoraServer(ClientAuthenticator authenticator, ServerConfig config,
                                  List<ClientRegistration> pendingRegistrations) {
        this.authenticator = authenticator;
        this.config = config;
        this.pendingRegistrations = pendingRegistrations;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 启动服务器并监听随机可用端口。幂等：重复调用忽略。
     */
    public synchronized void start() throws Exception {
        if (started) {
            return;
        }
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:linkora_testkit_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcServerSchema.migrate(dataSource);

        JdbcClientRegistry registry = new JdbcClientRegistry(dataSource);
        for (ClientRegistration registration : pendingRegistrations) {
            registry.register(registration);
        }

        server = LinkoraServer.builder()
                .clientAuthenticator(authenticator)
                .clientRegistry(registry)
                .messageRepository(new JdbcMessageRepository(dataSource))
                .deliveryRepository(new JdbcDeliveryRepository(dataSource))
                .cursorRepository(new JdbcClientCursorRepository(dataSource))
                .config(config)
                .build();
        server.start();

        jetty = new Server(0);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        jetty.setHandler(context);
        context.addServlet(new ServletHolder(
                new SsePushServlet(server.sseSubscribeHandler(), server.sseConnectionRegistry())), "/events");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ingestHttpHandler(), server.getMaxBodyBytes())), "/messages");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ackHttpHandler(), server.getMaxBodyBytes())), "/acks");
        jetty.start();
        port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
        started = true;
    }

    /** 客户端接入根地址（SSE/上报/ACK 三端点的公共前缀）。 */
    public String baseUrl() {
        requireStarted();
        return "http://127.0.0.1:" + port;
    }

    public int port() {
        requireStarted();
        return port;
    }

    /** 底层 LinkoraServer 实例（用于 publish、查询消息状态等断言）。 */
    public LinkoraServer server() {
        requireStarted();
        return server;
    }

    /** 便捷下发：向指定客户端推送一条业务消息。 */
    public PublishResult publish(String clientId, String messageType, byte[] payload) {
        return server.publish(clientId, messageType, payload);
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        try {
            jetty.stop();
        } catch (Exception e) {
            // 忽略容器停止异常
        }
        server.close();
    }

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("EmbeddedLinkoraServer 尚未 start()");
        }
    }

    public static final class Builder {
        private ClientAuthenticator authenticator = new AllowAllClientAuthenticator();
        private ServerConfig config = ServerConfig.builder().build();
        private final List<ClientRegistration> registrations = new CopyOnWriteArrayList<ClientRegistration>();

        /** 自定义认证器；默认放行全部（仅测试环境）。 */
        public Builder clientAuthenticator(ClientAuthenticator authenticator) {
            if (authenticator == null) {
                throw new IllegalArgumentException("authenticator 不能为 null");
            }
            this.authenticator = authenticator;
            return this;
        }

        /** 完整覆盖服务端配置；默认全默认值。 */
        public Builder config(ServerConfig config) {
            if (config == null) {
                throw new IllegalArgumentException("config 不能为 null");
            }
            this.config = config;
            return this;
        }

        /** 预注册一个启用的客户端（等价 registerClient(clientId, true)）。 */
        public Builder registerClient(String clientId) {
            return registerClient(clientId, true);
        }

        /** 预注册客户端（可指定是否启用）。 */
        public Builder registerClient(String clientId, boolean enabled) {
            registrations.add(new ClientRegistration(clientId, enabled, System.currentTimeMillis()));
            return this;
        }

        public EmbeddedLinkoraServer build() {
            return new EmbeddedLinkoraServer(authenticator, config, registrations);
        }
    }
}
