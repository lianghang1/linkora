package com.linkora.server.storage.jdbc;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.DeliveryRecord;
import org.junit.Assume;
import org.junit.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 宿主事务接管模式整套回归（真实 MySQL）：连接已处于手动提交（autoCommit=false，
 * 模拟宿主事务管理器绑定的代理连接，close 被宿主抑制）时，全部仓储写方法
 * （注册表、消息、投递、seq 分配、游标）必须加入宿主当前事务——
 * 严禁中途 commit/rollback，宿主提交整体生效、宿主回滚整体消失；
 * 仓储不得破坏连接的 autoCommit 状态。事务性发件箱语义即依赖此模式。
 *
 * <p>MySQL DDL 隐式提交（与 SQLite 事务性 DDL 不同）：迁移接管时建表语句会
 * 立即全局可见，事务性只有版本记录与 DML——本套测试聚焦 DML 写路径。</p>
 *
 * <pre>
 *   mvn -pl linkora-server-storage-jdbc "-Dtest=JdbcServerHostTransactionMySqlIT" test \
 *       "-Dlinkora.it.mysql=true" "-Dlinkora.it.mysql.password=密码"
 * </pre>
 *
 * @author lianghang
 */
public class JdbcServerHostTransactionMySqlIT {

    private static final long NOW = System.currentTimeMillis();

    private static boolean enabled() {
        return Boolean.getBoolean("linkora.it.mysql");
    }

    /** 与 JdbcServerStorageMySqlIT 相同的 DataSource 构造（库不存在自动创建）。 */
    private static DataSource mysqlDataSource() throws SQLException {
        String url = System.getProperty("linkora.it.mysql.url",
                "jdbc:mysql://127.0.0.1:3306/linkora?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8&createDatabaseIfNotExist=true");
        String user = System.getProperty("linkora.it.mysql.user", "root");
        String password = System.getProperty("linkora.it.mysql.password");
        Connection probe = DriverManager.getConnection(url, user, password);
        probe.close();
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url, user, password);
            }

            @Override
            public Connection getConnection(String username, String pass) throws SQLException {
                return DriverManager.getConnection(url, username, pass);
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
                DriverManager.setLoginTimeout(seconds);
            }

            @Override
            public int getLoginTimeout() {
                return DriverManager.getLoginTimeout();
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
    }

    private static void cleanSchema(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            // MDL 元数据锁等待上限 30 秒：若残留事务持锁，快速失败而非永久挂死
            st.execute("SET SESSION lock_wait_timeout = 30");
            for (String table : new String[]{"lk_delivery", "lk_message", "lk_client_cursor",
                    "lk_client_seq", "lk_client", "lk_schema_version"}) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static DataSource freshMySql() throws SQLException {
        DataSource ds = mysqlDataSource();
        cleanSchema(ds);
        JdbcServerSchema.migrate(ds);
        return ds;
    }

    /** 模拟宿主事务：绑定的手动提交连接，close 被宿主抑制，仓储不得真正关闭。 */
    private static DataSource ambientDataSource(DataSource real, final Connection ambient) {
        final Connection shared = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    try {
                        return method.invoke(ambient, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new DataSource() {
            @Override
            public Connection getConnection() {
                return shared;
            }

            @Override
            public Connection getConnection(String u, String p) {
                return shared;
            }

            @Override
            public <T> T unwrap(Class<T> iface) throws SQLException {
                throw new SQLException("not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
    }

    /**
     * 防御性清理：无论用例成功或断言失败，都必须释放 ambient 连接的未提交事务。
     * 否则该事务持有的 MDL 元数据锁会让后续用例的 DROP TABLE 永久阻塞。
     */
    private static void closeAmbient(Connection ambient) {
        try {
            ambient.rollback();
        } catch (SQLException ignore) {
            // 清理阶段异常不影响主流程
        }
        try {
            ambient.close();
        } catch (SQLException ignore) {
            // 清理阶段异常不影响主流程
        }
    }

    /** 宿主事务内的一次完整业务写入：注册 + 消息 + 投递 + seq + 游标。 */
    private static void writeOnce(DataSource ambientDs) {
        JdbcClientRegistry registry = new JdbcClientRegistry(ambientDs);
        registry.register(new ClientRegistration("store-tx", true, NOW));

        JdbcMessageRepository messages = new JdbcMessageRepository(ambientDs);
        messages.save(LinkoraMessage.builder()
                .messageId("msg-tx-1")
                .clientId("store-tx")
                .messageType("ORDER_CREATED")
                .payload(new byte[]{1, 2, 3})
                .createdAt(NOW)
                .schemaVersion(2)
                .build());

        JdbcDeliveryRepository deliveries = new JdbcDeliveryRepository(ambientDs);
        deliveries.init(new DeliveryRecord("msg-tx-1", "store-tx", 1,
                MessageState.CREATED, 0, null, null, NOW, NOW));
        deliveries.nextDeliverySeq("store-tx");

        JdbcClientCursorRepository cursors = new JdbcClientCursorRepository(ambientDs);
        cursors.saveLastDeliveredSeq("store-tx", 1);
    }

    /** 观察连接统计各表行数（不存在返回 0）。 */
    private static long countRows(DataSource real, String table, long expectedIfMissing)
            throws SQLException {
        try (Connection observer = real.getConnection();
             ResultSet rs = observer.createStatement().executeQuery(
                     "SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * 用例 1（整套核心）：宿主事务内五类写入同生共死——
     * 未提交不可见、回滚全部消失、重新执行后提交全部生效。
     */
    @Test
    public void transactionalOutboxWritesJoinHostTransaction() throws Exception {
        Assume.assumeTrue(enabled());
        DataSource real = freshMySql();
        Connection ambient = real.getConnection();
        try {
            ambient.setAutoCommit(false);
            DataSource ambientDs = ambientDataSource(real, ambient);

            writeOnce(ambientDs);

            // 宿主未提交：注册表/消息/投递/seq/游标对外均不可见
            assertEquals(0, countRows(real, "lk_client", 0));
            assertEquals(0, countRows(real, "lk_message", 0));
            assertEquals(0, countRows(real, "lk_delivery", 0));
            assertEquals(0, countRows(real, "lk_client_seq", 0));
            assertEquals(0, countRows(real, "lk_client_cursor", 0));

            // 宿主回滚：全部消失
            ambient.rollback();
            assertEquals(0, countRows(real, "lk_client", 0));
            assertEquals(0, countRows(real, "lk_message", 0));
            assertEquals(0, countRows(real, "lk_client_seq", 0));

            // 重新执行 + 宿主提交：全部生效
            writeOnce(ambientDs);
            ambient.commit();
            assertEquals(1, countRows(real, "lk_client", 0));
            assertEquals(1, countRows(real, "lk_message", 0));
            assertEquals(1, countRows(real, "lk_delivery", 0));
            assertEquals(1, countRows(real, "lk_client_seq", 0));
            assertEquals(1, countRows(real, "lk_client_cursor", 0));

            // seq 分配结果落库为 1
            try (Connection observer = real.getConnection();
                 ResultSet rs = observer.createStatement().executeQuery(
                         "SELECT last_seq FROM lk_client_seq WHERE client_id = 'store-tx'")) {
                rs.next();
                assertEquals(1, rs.getLong(1));
            }
        } finally {
            closeAmbient(ambient);
        }
    }

    /** 用例 2：seq 分配在宿主事务内的独立回滚语义。 */
    @Test
    public void seqAllocationRollbackWithHostTransaction() throws Exception {
        Assume.assumeTrue(enabled());
        DataSource real = freshMySql();
        Connection ambient = real.getConnection();
        try {
            ambient.setAutoCommit(false);

            JdbcDeliveryRepository ambientRepo =
                    new JdbcDeliveryRepository(ambientDataSource(real, ambient));
            assertEquals(1, ambientRepo.nextDeliverySeq("c-ambient"));

            // 未提交不可见（若仓储中途 commit 会破坏这一点）
            assertEquals(0, countRows(real, "lk_client_seq", 0));

            // 回滚消失
            ambient.rollback();
            assertEquals(0, countRows(real, "lk_client_seq", 0));

            // 连接状态保持 + 提交后独立分配正常续号
            assertFalse("仓储不得破坏宿主连接的 autoCommit 状态", ambient.getAutoCommit());
            ambient.commit();
            JdbcDeliveryRepository standalone = new JdbcDeliveryRepository(real);
            assertEquals(1, standalone.nextDeliverySeq("c-ambient"));
        } finally {
            closeAmbient(ambient);
        }
    }

    /** 用例 3：宿主提交后，CAS 推进（自动提交连接）与宿主内写入共存。 */
    @Test
    public void casAdvancesAfterHostCommit() throws Exception {
        Assume.assumeTrue(enabled());
        DataSource real = freshMySql();
        Connection ambient = real.getConnection();
        try {
            ambient.setAutoCommit(false);
            DataSource ambientDs = ambientDataSource(real, ambient);

            JdbcDeliveryRepository ambientRepo = new JdbcDeliveryRepository(ambientDs);
            ambientRepo.init(new DeliveryRecord("m-cas", "c-1", 1,
                    MessageState.CREATED, 0, null, null, NOW, NOW));
            ambient.commit();

            JdbcDeliveryRepository standalone = new JdbcDeliveryRepository(real);
            assertTrue(standalone.compareAndSetState("m-cas", "c-1",
                    MessageState.CREATED, MessageState.RECEIVED, null, null, 0));
            assertEquals(MessageState.RECEIVED,
                    standalone.find("m-cas", "c-1").get().getState());
        } finally {
            closeAmbient(ambient);
        }
    }
}
