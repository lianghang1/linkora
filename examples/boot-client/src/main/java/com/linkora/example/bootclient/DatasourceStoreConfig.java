package com.linkora.example.bootclient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * datasource 存储模式的宿主数据源（仅 {@code spring.profiles.active=datasource} 时装配）。
 *
 * <p>演示用 SQLite 文件库充当"宿主业务库"（business_shipment 与 lk_client_outbox
 * 同库，OrderStatusService.updateStatusAtomically 的事务演示完整）；生产替换为
 * 业务库连接池（MySQL 等）并按需引入 HikariCP——本类刻意不引入，使默认 sqlite
 * 模式保持零外部依赖。starter 会将该唯一候选 DataSource 以事务感知代理包装后
 * 作为客户端本地队列存储。</p>
 *
 * @author lianghang
 */
@Configuration
@Profile("datasource")
public class DatasourceStoreConfig {

    @Bean
    public DataSource businessDataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:./linkora/business-client.db");
        return ds;
    }
}
