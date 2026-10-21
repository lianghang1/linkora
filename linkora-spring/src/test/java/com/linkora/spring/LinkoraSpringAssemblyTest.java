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
import org.h2.jdbcx.JdbcDataSource;
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
 */
public class LinkoraSpringAssemblyTest {

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
        public JdbcDataSource linkoraDataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:linkora_spring_test_" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1");
            JdbcServerSchema.migrate(ds);
            return ds;
        }

        @Bean
        public ClientAuthenticator linkoraClientAuthenticator() {
            return new AllowAllClientAuthenticator();
        }

        @Bean
        public ClientRegistry linkoraClientRegistry(JdbcDataSource linkoraDataSource) {
            return new JdbcClientRegistry(linkoraDataSource);
        }

        @Bean
        public MessageRepository linkoraMessageRepository(JdbcDataSource linkoraDataSource) {
            return new JdbcMessageRepository(linkoraDataSource);
        }

        @Bean
        public DeliveryRepository linkoraDeliveryRepository(JdbcDataSource linkoraDataSource) {
            return new JdbcDeliveryRepository(linkoraDataSource);
        }

        @Bean
        public ClientCursorRepository linkoraCursorRepository(JdbcDataSource linkoraDataSource) {
            return new JdbcClientCursorRepository(linkoraDataSource);
        }
    }
}
