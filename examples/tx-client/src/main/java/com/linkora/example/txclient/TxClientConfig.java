package com.linkora.example.txclient;

import com.linkora.client.LinkoraClient;
import com.linkora.core.storage.client.ClientStore;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 客户端事务接管装配：宿主 H2 业务库 + Spring 事务管理 + 自实现 {@link ClientStore}。
 *
 * <p>关键点：ClientStore 连接全部取自 {@link TransactionAwareDataSourceProxy}
 * 包装的宿主数据源，且代理目标与事务管理器持有的是同一个原始数据源，
 * 事务资源按原始数据源为键共享，outbox 写入因此能加入宿主事务。</p>
 *
 * @author lianghang
 */
@Configuration
@EnableTransactionManagement
public class TxClientConfig {

    private final String serverUrl;

    /** @param serverUrl 对端服务端地址（示例中由 EmbeddedLinkoraServer.baseUrl() 提供） */
    public TxClientConfig(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @Bean
    public JdbcDataSource linkoraH2DataSource() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:linkora_tx_client_demo;DB_CLOSE_DELAY=-1");
        // 迁移在原始连接上执行（在参与宿主事务之前完成建表）
        TxClientSchema.migrate(ds);
        return ds;
    }

    @Bean
    public PlatformTransactionManager transactionManager(JdbcDataSource linkoraH2DataSource) {
        return new DataSourceTransactionManager(linkoraH2DataSource);
    }

    /**
     * 宿主业务与 ClientStore 共用的"事务感知"数据源：
     * 事务内取绑定连接（加入宿主事务），事务外取独立连接。
     */
    @Bean
    public TransactionAwareDataSourceProxy linkoraDataSource(JdbcDataSource linkoraH2DataSource) {
        return new TransactionAwareDataSourceProxy(linkoraH2DataSource);
    }

    @Bean
    public JdbcTemplate businessJdbc(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new JdbcTemplate(linkoraDataSource);
    }

    @Bean
    public ClientStore txClientStore(TransactionAwareDataSourceProxy linkoraDataSource) {
        return new H2ClientStore(linkoraDataSource);
    }

    @Bean
    public OrderService orderService(JdbcTemplate businessJdbc, LinkoraClient linkoraClient) {
        return new OrderService(businessJdbc, linkoraClient);
    }

    @Bean
    public LinkoraClient linkoraClient(ClientStore txClientStore) {
        return LinkoraClient.builder()
                .serverUrl(serverUrl)
                .clientId("tx-client-demo")
                .clientStore(txClientStore)
                .build();
    }
}
