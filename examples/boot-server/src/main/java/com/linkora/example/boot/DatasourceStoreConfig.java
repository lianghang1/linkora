package com.linkora.example.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;

/**
 * datasource 存储模式的宿主数据源（仅 {@code spring.profiles.active=datasource} 时装配）。
 *
 * <p>演示用 SQLite 文件库充当"宿主业务库"（业务表与 lk_* 表同库，事务演示完整）；
 * 生产替换为业务库连接池（MySQL 等）并按需引入 HikariCP——本类刻意不引入，
 * 使默认 sqlite 模式保持零外部依赖。starter 会将该唯一候选 DataSource 以
 * 事务感知代理包装为 linkoraServerStorage。</p>
 *
 * @author lianghang
 */
@Configuration
@Profile("datasource")
public class DatasourceStoreConfig {

    @Bean
    public DataSource businessDataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:./linkora/business.db");
        return ds;
    }
}
