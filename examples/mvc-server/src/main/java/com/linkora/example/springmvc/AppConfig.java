package com.linkora.example.springmvc;

import com.linkora.core.storage.embedded.EmbeddedSqliteDatabase;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;

/**
 * Spring MVC 宿主装配：宿主只需提供四个存储 SPI bean，
 * {@link LinkoraServerConfiguration} 负责组装 LinkoraServer 并管理生命周期。
 *
 * <p>存储模式（0.5.0，与 Boot 示例对等），由系统属性 {@code linkora.store} 切换：</p>
 * <ul>
 *   <li>{@code sqlite}（默认）：内嵌 SQLite 文件（{@code ./linkora/linkora-mvc-server.db}），
 *       零配置起步；</li>
 *   <li>{@code mysql}：远程 MySQL（datasource 模式，业务消息同库同事务），
 *       测试可用 {@code linkora.datasource.url} 指向 H2 内存库覆盖。</li>
 * </ul>
 *
 * @author lianghang
 */
@Configuration
@EnableWebMvc
// 纯 Spring MVC 宿主没有 Boot 的自动配置：注解事务必须显式开启，
// 否则 @Transactional 静默失效（无代理、无回滚）
@org.springframework.transaction.annotation.EnableTransactionManagement
@ComponentScan("com.linkora.example.springmvc")
@Import(LinkoraServerConfiguration.class)
public class AppConfig implements WebMvcConfigurer {

    private static final String DEFAULT_URL =
            "jdbc:mysql://115.159.41.195:3306/linkora?useSSL=false"
                    + "&allowPublicKeyRetrieval=true&characterEncoding=UTF-8"
                    + "&serverTimezone=Asia/Shanghai";
    private static final String DEFAULT_USER = "linkora";
    private static final String DEFAULT_PASSWORD = "b5YiM6Axm7zKX3ZD";

    @Bean(destroyMethod = "close")
    public EmbeddedSqliteDatabase embeddedDb() {
        return EmbeddedSqliteDatabase.atFile(
                System.getProperty("linkora.db-path", "./linkora/linkora-mvc-server.db"));
    }

    @Bean
    public DataSource linkoraDataSource(EmbeddedSqliteDatabase embeddedDb) {
        String store = System.getProperty("linkora.store", "sqlite");
        if ("mysql".equalsIgnoreCase(store)) {
            String url = System.getProperty("linkora.datasource.url", DEFAULT_URL);
            DataSource ds;
            if (url.startsWith("jdbc:h2:")) {
                org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
                h2.setURL(url);
                ds = h2;
            } else {
                com.mysql.cj.jdbc.MysqlDataSource mysql = new com.mysql.cj.jdbc.MysqlDataSource();
                mysql.setURL(url);
                mysql.setUser(System.getProperty("linkora.datasource.user", DEFAULT_USER));
                mysql.setPassword(System.getProperty("linkora.datasource.password", DEFAULT_PASSWORD));
                ds = mysql;
            }
            // 启动前完成 schema 迁移（幂等，崩溃续跑）
            JdbcServerSchema.migrate(ds);
            // 事务感知包装：宿主 @Transactional 内的 publish 随业务原子提交
            return com.linkora.spring.TxAwareDataSources.wrap(ds);
        }
        // sqlite（默认）：内嵌文件库，零配置起步；迁移同样幂等
        DataSource ds = embeddedDb.dataSource();
        JdbcServerSchema.migrate(ds);
        return com.linkora.spring.TxAwareDataSources.wrap(ds);
    }

    /** 事务管理器：绑定存储数据源，@Transactional（同库同事务演示）依赖它。 */
    @Bean
    public org.springframework.transaction.PlatformTransactionManager linkoraTransactionManager(
            DataSource linkoraDataSource) {
        return new org.springframework.jdbc.datasource.DataSourceTransactionManager(linkoraDataSource);
    }

    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        // 演示用放行认证器；生产环境替换为 HmacClientAuthenticator（docs/security.md）
        return new AllowAllClientAuthenticator();
    }

    @Bean
    public ClientRegistry linkoraClientRegistry(DataSource linkoraDataSource) {
        return new JdbcClientRegistry(linkoraDataSource);
    }

    @Bean
    public MessageRepository linkoraMessageRepository(DataSource linkoraDataSource) {
        return new JdbcMessageRepository(linkoraDataSource);
    }

    @Bean
    public DeliveryRepository linkoraDeliveryRepository(DataSource linkoraDataSource) {
        return new JdbcDeliveryRepository(linkoraDataSource);
    }

    @Bean
    public ClientCursorRepository linkoraCursorRepository(DataSource linkoraDataSource) {
        return new JdbcClientCursorRepository(linkoraDataSource);
    }
}
