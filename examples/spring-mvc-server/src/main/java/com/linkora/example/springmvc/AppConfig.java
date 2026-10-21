package com.linkora.example.springmvc;

import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import com.linkora.spring.LinkoraServerConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Spring MVC 宿主装配：宿主只需提供四个存储 SPI bean，
 * {@link LinkoraServerConfiguration} 负责组装 LinkoraServer 并管理生命周期。
 *
 * @author lianghang
 */
@Configuration
@EnableWebMvc
@ComponentScan("com.linkora.example.springmvc")
@Import(LinkoraServerConfiguration.class)
public class AppConfig implements WebMvcConfigurer {

    @Bean
    public JdbcDataSource linkoraDataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:"
                + Paths.get("data", "linkora-spring-mvc").toAbsolutePath()
                + ";AUTO_SERVER=FALSE");
        // 启动前完成 schema 迁移（幂等，崩溃续跑）
        JdbcServerSchema.migrate(ds);
        return ds;
    }

    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        // 演示用放行认证器；生产环境替换为 HmacClientAuthenticator（docs/security.md）
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
