package com.linkora.spring;

import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.LinkoraServer;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.sqlite.SQLiteDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletRegistration;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * linkora-spring 装配辅助测试：SPI bean 组装 LinkoraServer + Servlet 注册。
 *
 * @author lianghang
 */
public class LinkoraSpringAssemblyTest {

    /** SQLite 文件库等价实现（替代 H2 内存库：按名称隔离的 temp 目录，JVM 退出自动清理）。 */
    private static String sqliteUrl(String name) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("linkora-test-" + name);
            java.io.File f = dir.resolve("db.sqlite").toFile();
            f.deleteOnExit();
            dir.toFile().deleteOnExit();
            return "jdbc:sqlite:" + f.getAbsolutePath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建测试库目录失败: " + name, e);
        }
    }

    private AnnotationConfigApplicationContext context;

    @Before
    public void setUp() {
        context = new AnnotationConfigApplicationContext(TestStorageConfig.class,
                LinkoraServerConfiguration.class);
    }

    @After
    public void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    public void serverBeanAssembledAndStartedFromSpiBeans() {
        LinkoraServer server = context.getBean(LinkoraServer.class);
        assertNotNull("应从容器 SPI bean 组装出 LinkoraServer", server);
        assertTrue("上下文刷新后服务端应已启动（initMethod=start）", server.isRunning());
    }

    @Test
    public void serverClosedOnContextShutdown() {
        LinkoraServer server = context.getBean(LinkoraServer.class);
        context.close();
        assertFalse("容器销毁后服务端应已关闭（destroyMethod=close）", server.isRunning());
        context = null;
    }

    @Test
    public void servletsRegisteredIntoServletContext() throws Exception {
        LinkoraServer server = context.getBean(LinkoraServer.class);

        ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        handler.setContextPath("/");
        Server jetty = new Server(0);
        jetty.setHandler(handler);

        LinkoraServlets.register(server, handler.getServletContext(), "/linkora");
        jetty.start();
        try {
            javax.servlet.ServletContext ctx = handler.getServletContext();
            assertMapping(ctx, "linkoraSsePushServlet", "/linkora/events");
            assertMapping(ctx, "linkoraIngestServlet", "/linkora/messages");
            assertMapping(ctx, "linkoraAckServlet", "/linkora/acks");
        } finally {
            jetty.stop();
        }
    }

    private static void assertMapping(javax.servlet.ServletContext ctx,
                                      String name, String expectedMapping) {
        ServletRegistration registration = ctx.getServletRegistration(name);
        assertNotNull("应注册 Servlet: " + name, registration);
        Collection<String> mappings = registration.getMappings();
        assertTrue("映射应包含 " + expectedMapping + "，实际 " + mappings,
                mappings.contains(expectedMapping));
    }

    @Configuration
    static class TestStorageConfig {

        @Bean
        public SQLiteDataSource linkoraDataSource() {
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl(sqliteUrl("linkora_spring_test_" + System.nanoTime()));
            JdbcServerSchema.migrate(ds);
            return ds;
        }

        @Bean
        public ClientAuthenticator linkoraClientAuthenticator() {
            return new AllowAllClientAuthenticator();
        }

        @Bean
        public ClientRegistry linkoraClientRegistry(SQLiteDataSource linkoraDataSource) {
            return new JdbcClientRegistry(linkoraDataSource);
        }

        @Bean
        public MessageRepository linkoraMessageRepository(SQLiteDataSource linkoraDataSource) {
            return new JdbcMessageRepository(linkoraDataSource);
        }

        @Bean
        public DeliveryRepository linkoraDeliveryRepository(SQLiteDataSource linkoraDataSource) {
            return new JdbcDeliveryRepository(linkoraDataSource);
        }

        @Bean
        public ClientCursorRepository linkoraCursorRepository(SQLiteDataSource linkoraDataSource) {
            return new JdbcClientCursorRepository(linkoraDataSource);
        }
    }
}
