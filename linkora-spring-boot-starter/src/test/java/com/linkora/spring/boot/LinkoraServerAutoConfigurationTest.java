package com.linkora.spring.boot;

import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.LinkoraServer;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端自动装配测试：默认装配、路径覆盖、客户端注册、宿主 bean 覆盖。
 *
 * @author lianghang
 */
class LinkoraServerAutoConfigurationTest {

    @Configuration
    @Import(App.DataSourceConfig.class)
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class App {

        @Configuration
        static class DataSourceConfig {
            @Bean
            DataSource linkoraDataSource() {
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL("jdbc:h2:mem:linkora_boot_server_" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1");
                return ds;
            }
        }
    }

    @Configuration
    static class HostOverrideConfig {
        @Bean
        MessageRepository customMessageRepository() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:linkora_boot_override_" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1");
            return new JdbcMessageRepository(ds);
        }
    }

    @Test
    void serverStartedWithDefaultEndpoints() {
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(App.class)
                        .run("--server.port=0", "--linkora.server.allow-anonymous=true");
        try {
            LinkoraServer server = context.getBean(LinkoraServer.class);
            assertNotNull(server);
            assertTrue(server.isRunning(), "上下文启动后服务端应已 start");

            ClientRegistry registry = context.getBean(ClientRegistry.class);
            assertTrue(registry instanceof JdbcClientRegistry);

            assertServletMapping(context, "linkoraSsePushServlet", "/events");
            assertServletMapping(context, "linkoraIngestServlet", "/messages");
            assertServletMapping(context, "linkoraAckServlet", "/acks");
        } finally {
            context.close();
        }
    }

    @Test
    void customPathsAndClientRegistration() {
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(App.class).run("--server.port=0",
                        "--linkora.server.events-path=/sse/events",
                        "--linkora.server.register-clients.demo-client=true",
                        "--linkora.server.allow-anonymous=true");
        try {
            assertServletMapping(context, "linkoraSsePushServlet", "/sse/events");
            ClientRegistry registry = context.getBean(ClientRegistry.class);
            assertTrue(registry.findByClientId("demo-client").isPresent(),
                    "register-clients 应在启动时注册 demo-client");
        } finally {
            context.close();
        }
    }

    @Test
    void hostBeanOverridesStarterDefaults() {
        ConfigurableApplicationContext context =
                new SpringApplicationBuilder(App.class, HostOverrideConfig.class)
                        .run("--server.port=0", "--linkora.server.allow-anonymous=true");
        try {
            assertEquals(1, context.getBeansOfType(MessageRepository.class).size(),
                    "容器内只应存在宿主自定义的 MessageRepository（starter 默认退位）");
        } finally {
            context.close();
        }
    }

    private static void assertServletMapping(ApplicationContext context,
                                             String beanName, String expectedPath) {
        ServletRegistrationBean<?> bean = context.getBean(beanName, ServletRegistrationBean.class);
        assertNotNull(bean, "缺少 Servlet 注册 bean: " + beanName);
        Collection<String> mappings = bean.getUrlMappings();
        assertTrue(mappings.contains(expectedPath),
                "映射应包含 " + expectedPath + "，实际 " + mappings);
    }
}
