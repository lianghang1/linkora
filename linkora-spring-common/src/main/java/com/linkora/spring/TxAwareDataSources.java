package com.linkora.spring;

import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import javax.sql.DataSource;

/**
 * 事务感知数据源包装（两端 publish 同事务共用基建）。
 *
 * <p>把宿主的池化数据源（如 HikariCP）包一层
 * {@link TransactionAwareDataSourceProxy} 后：</p>
 * <ul>
 *   <li>宿主 {@code @Transactional} 事务内的 {@code getConnection()} 返回
 *       当前事务连接——JDBC 客户端存储/服务端仓储的写入自动加入业务事务，
 *       业务回滚则消息写入一起回滚（transactional outbox）；</li>
 *   <li>事务外取连接则直连数据源，行为不变。</li>
 * </ul>
 *
 * <p>注意：包装仅影响"参与宿主事务"这一件事；连接池仍是宿主的，无需改配置。</p>
 *
 * @author lianghang
 */
public final class TxAwareDataSources {

    private TxAwareDataSources() {
    }

    /** 包装为事务感知数据源；已是代理则原样返回，避免双层包装。 */
    public static DataSource wrap(DataSource dataSource) {
        if (dataSource instanceof TransactionAwareDataSourceProxy) {
            return dataSource;
        }
        return new TransactionAwareDataSourceProxy(dataSource);
    }
}
