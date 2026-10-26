package com.linkora.spring.boot;

import com.linkora.adminconsole.AdminConsoleFilter;
import com.linkora.adminconsole.AdminConsoleServlet;
import com.linkora.server.LinkoraServer;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 运维控制台自动装配：显式开启（{@code linkora.admin-console.enabled=true}）
 * 且宿主存在 {@link LinkoraServer} 时，挂载控制台 Servlet 与 Bearer Token 过滤器。
 *
 * <p>默认不装配；{@code enabled=true} 而未配置 {@code token} 时装配期快速失败，
 * 杜绝无鉴权运维端点意外暴露。控制台与业务端点共享宿主容器的同一端口。</p>
 *
 * @author lianghang
 */
@Configuration
@ConditionalOnClass({AdminConsoleServlet.class, LinkoraServer.class})
@ConditionalOnProperty(prefix = "linkora.admin-console", name = "enabled",
        havingValue = "true")
@ConditionalOnBean(LinkoraServer.class)
@AutoConfigureAfter(LinkoraServerAutoConfiguration.class)
@EnableConfigurationProperties(LinkoraAdminConsoleProperties.class)
public class LinkoraAdminConsoleAutoConfiguration {

    /** 控制台令牌必填校验：开启但未配置令牌 = 配置错误，快速失败。 */
    public LinkoraAdminConsoleAutoConfiguration(LinkoraAdminConsoleProperties properties) {
        if (properties.getToken() == null || properties.getToken().trim().isEmpty()) {
            throw new IllegalStateException(
                    "linkora.admin-console.enabled=true 时必须配置 linkora.admin-console.token");
        }
    }

    @Bean
    public ServletRegistrationBean<AdminConsoleServlet> linkoraAdminConsoleServlet(
            LinkoraServer server, LinkoraAdminConsoleProperties properties) {
        ServletRegistrationBean<AdminConsoleServlet> bean =
                new ServletRegistrationBean<AdminConsoleServlet>(
                        new AdminConsoleServlet(server), properties.getPath() + "/*");
        bean.setName("linkoraAdminConsoleServlet");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<AdminConsoleFilter> linkoraAdminConsoleFilter(
            LinkoraAdminConsoleProperties properties) {
        FilterRegistrationBean<AdminConsoleFilter> bean =
                new FilterRegistrationBean<AdminConsoleFilter>(
                        new AdminConsoleFilter(properties.getToken()));
        bean.addUrlPatterns(properties.getPath() + "/*");
        bean.setName("linkoraAdminConsoleFilter");
        bean.setOrder(0);
        return bean;
    }
}
