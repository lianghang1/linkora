package com.linkora.example.bootclient;

import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 墓位业务库（模拟墓园既有系统）：独立 SQLite 文件，本客户端只读。
 * 生产替换为只读 MySQL 连接即可。
 *
 * @author lianghang
 */
@Configuration
@EnableScheduling
public class TombBusinessDbConfig {

    @Bean
    public javax.sql.DataSource tombBusinessDataSource() {
        org.sqlite.SQLiteDataSource ds = new org.sqlite.SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:./linkora/business.db");
        return ds;
    }
}
