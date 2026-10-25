package com.linkora.example.boot;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 事务配置：显式声明事务管理器并绑定服务端存储数据源（linkoraServerStorage）。
 *
 * <p>需要显式声明的原因：datasource 模式下容器内同时存在宿主业务 DataSource
 * 与 linkoraServerStorage（事务感知包装）两个 DataSource Bean，Boot 的
 * DataSourceTransactionManagerAutoConfiguration 要求单候选才生效——双候选时
 * 不会自动创建，@Transactional 将静默失效。显式声明后，业务方法与
 * server.publish 的写入统一由该管理器管理，保证同库同事务语义。</p>
 *
 * @author lianghang
 */
@Configuration
public class TransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("linkoraServerStorage") DataSource storageDataSource) {
        return new DataSourceTransactionManager(storageDataSource);
    }
}
