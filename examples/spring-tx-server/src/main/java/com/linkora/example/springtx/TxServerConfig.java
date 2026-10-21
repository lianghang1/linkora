package com.linkora.example.springtx;

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
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 服务端宿主事务接管装配：Linkora 的四个存储 SPI 复用 JDBC 参考实现，
 * 但连接统一取自 {@link TransactionAwareDataSourceProxy}——
 * 宿主业务事务（@Transactional）内发生的 publish/ack/ingest 持久化自动加入当前事务；
 * Linkora 编排线程（重试扫描等）在事务外逐语句提交，行为不变。
 *
 * <p>装配仍走 linkora-spring 的 {@link LinkoraServerConfiguration}：
 * 宿主提供 SPI bean 即可，无需关心 LinkoraServer 组装细节。</p>
 */
@Configuration
@EnableTransactionManagement
@Import(LinkoraServerConfiguration.class)
public class TxServerConfig {

    public static final String DEMO_CLIENT_ID = "demo-store";

    @Bean
    public JdbcDataSource linkoraH2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:linkora_tx_server_demo;DB_CLOSE_DELAY=-1");
        // schema 迁移在原始连接上执行（在参与宿主事务之前完成建表）
        JdbcServerSchema.migrate(ds);
        return ds;
    }

    @Bean
    public PlatformTransactionManager transactionManager(JdbcDataSource linkoraH2DataSource) {
        return new DataSourceTransactionManager(linkoraH2DataSource);
    }

    /**
     * 事务感知数据源：事务内返回绑定连接（加入宿主事务），
     * 事务外返回独立连接（逐语句自动提交）。
     */
    @Bean
    public TransactionAwareDataSourceProxy linkoraDataSource(JdbcDataSource linkoraH2DataSource) {
        return new TransactionAwareDataSourceProxy(linkoraH2DataSource);
    }

    @Bean
    public JdbcTemplate businessJdbc(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new JdbcTemplate(linkoraDataSource);
    }

    // ---------------- 四个存储 SPI：JDBC 参考实现 + 事务感知数据源 ----------------

    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        // 演示用放行认证器；生产环境替换为 HmacClientAuthenticator（docs/security.md）
        return new AllowAllClientAuthenticator();
    }

    @Bean
    public ClientRegistry linkoraClientRegistry(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new JdbcClientRegistry(linkoraDataSource);
    }

    @Bean
    public MessageRepository linkoraMessageRepository(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new JdbcMessageRepository(linkoraDataSource);
    }

    @Bean
    public DeliveryRepository linkoraDeliveryRepository(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new JdbcDeliveryRepository(linkoraDataSource);
    }

    @Bean
    public ClientCursorRepository linkoraCursorRepository(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new JdbcClientCursorRepository(linkoraDataSource);
    }

    /**
     * 业务服务必须经容器代理获得事务能力（直接 new 会绕过 @Transactional）。
     */
    @Bean
    public OrderService orderService(JdbcTemplate businessJdbc,
                                     com.linkora.server.LinkoraServer linkoraServer) {
        return new OrderService(businessJdbc, linkoraServer);
    }
}
