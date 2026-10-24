package com.linkora.spring.boot;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 运维控制台自动装配测试：默认关闭（零装配）、开启必须配 token（缺失快速失败）、
 * 正确配置时 Servlet + 过滤器按配置路径注册。
 *
 * @author lianghang
 */
class LinkoraAdminConsoleAutoConfigurationTest {

    @Configuration
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:linkora_admin_console_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            return ds;
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("linkora.server.allow-anonymous=true")
            .withConfiguration(AutoConfigurations.of(
                    LinkoraServerAutoConfiguration.class,
                    LinkoraAdminConsoleAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfig.class);

    @Test
    void consoleIsOffByDefault() {
        // 默认关闭：即使 admin-console 类在 classpath 上，也不装配任何控制台 bean
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("linkoraAdminConsoleServlet");
            assertThat(context).doesNotHaveBean("linkoraAdminConsoleFilter");
            // 服务端本身不受影响，照常装配
            assertThat(context).hasSingleBean(com.linkora.server.LinkoraServer.class);
        });
    }

    @Test
    void enabledWithoutTokenFailsFast() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LinkoraServerAutoConfiguration.class,
                        LinkoraAdminConsoleAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("linkora.admin-console.enabled=true")
                .run(context ->
                        assertThat(context).getFailure().hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @Test
    void enabledWithTokenRegistersServletAndFilter() {
        runner.withPropertyValues(
                        "linkora.server.allow-anonymous=true",
                        "linkora.admin-console.enabled=true",
                        "linkora.admin-console.token=unit-token",
                        "linkora.admin-console.path=/ops")
                .run(context -> {
                    assertThat(context).hasBean("linkoraAdminConsoleServlet");
                    ServletRegistrationBean<?> servlet =
                            context.getBean("linkoraAdminConsoleServlet", ServletRegistrationBean.class);
                    assertThat(servlet.getUrlMappings()).containsExactly("/ops/*");

                    FilterRegistrationBean<?> filter =
                            context.getBean("linkoraAdminConsoleFilter", FilterRegistrationBean.class);
                    assertThat(filter.getUrlPatterns()).containsExactly("/ops/*");
                });
    }

    /** 冒烟：真实 Boot 容器内端到端可用（复用本模块测试上下文，不起真实端口，装配级验证）。 */
    @Test
    void consoleBeansBindableInWebContext() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        LinkoraServerAutoConfiguration.class,
                        LinkoraAdminConsoleAutoConfiguration.class))
                .withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "linkora.server.allow-anonymous=true",
                        "linkora.admin-console.enabled=true",
                        "linkora.admin-console.token=unit-token")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServletRegistrationBean<?> servlet =
                            context.getBean("linkoraAdminConsoleServlet", ServletRegistrationBean.class);
                    assertThat(servlet.getUrlMappings()).containsExactly("/linkora-admin/*");
                });
    }
}
