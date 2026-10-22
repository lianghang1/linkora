package com.linkora.server.storage.jdbc;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.ClientRegistration;
import org.junit.Assume;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * JDBC 服务端存储 MySQL 8 真实库回归：迁移幂等、消息含二进制 payload 往返、
 * CAS 竞争、seq 并发分配、游标单调、注册表幂等。默认跳过，需真实库时开启：
 *
 * <pre>
 *   mvn -pl linkora-server "-Dtest=JdbcServerStorageMySqlIT" test \
 *       "-Dlinkora.it.mysql=true" "-Dlinkora.it.mysql.password=你的密码"
 * </pre>
 *
 * <p>可选属性：{@code linkora.it.mysql.url}（默认本机 3306 的 linkora 库，
 * 库不存在自动创建）、{@code linkora.it.mysql.user}（默认 root）。
 * 测试用例运行前清空 lk_* 表，不影响库中其他数据。</p>
 *
 * @author lianghang
 */
public class JdbcServerStorageMySqlIT {

    private static final long NOW = System.currentTimeMillis();

    private static boolean enabled() {
        return Boolean.getBoolean("linkora.it.mysql");
    }

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

    /** 清空 lk_* 表（专用于回归库；版本表也重建以保证迁移脚本完整重跑）。 */
    private static void cleanSchema(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
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

    // ---------------- 迁移 ----------------

    @Test
    public void migrationIsIdempotent() throws Exception {
        Assume.assumeTrue(enabled());
        DataSource ds = freshMySql();
        JdbcServerSchema.migrate(ds);
        JdbcServerSchema.migrate(ds);
        // 版本记录唯一且为最新
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM lk_schema_version WHERE version = ?")) {
            ps.setInt(1, JdbcServerSchema.LATEST_VERSION);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                org.junit.Assert.assertEquals(1, rs.getInt(1));
            }
        }
    }

    // ---------------- MessageRepository：二进制 payload 往返 ----------------

    @Test
    public void messageSaveFindRoundtripWithBinaryPayload() throws Exception {
        Assume.assumeTrue(enabled());
        JdbcMessageRepository repo = new JdbcMessageRepository(freshMySql());
        LinkoraMessage original = LinkoraMessage.builder()
                .messageId("msg-mysql-1")
                .clientId("store-001")
                .messageType("ORDER_CREATED")
                .payload(new byte[]{1, 2, 3, -5, 127, -128, 0, 64})
                .createdAt(NOW)
                .schemaVersion(2)
                .traceId("trace-mysql")
                .build();
        repo.save(original);
        LinkoraMessage loaded = repo.findByMessageId("msg-mysql-1").orElse(null);
        org.junit.Assert.assertEquals(original, loaded);
    }

    // ---------------- DeliveryRepository：CAS 并发唯一胜者 ----------------

    @Test
    public void deliveryCasConcurrentHasExactlyOneWinner() throws Exception {
        Assume.assumeTrue(enabled());
        DataSource ds = freshMySql();
        JdbcDeliveryRepository repo = new JdbcDeliveryRepository(ds);
        repo.init(new com.linkora.core.storage.server.DeliveryRecord(
                "m-race", "c-1", 1, MessageState.CREATED, 0, null, null, NOW, NOW));

        final CountDownLatch start = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger winners =
                new java.util.concurrent.atomic.AtomicInteger();
        final int threads = 4;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        boolean won = repo.compareAndSetState("m-race", "c-1",
                                MessageState.CREATED, MessageState.RECEIVED, null, null, 0, 0);
                        if (won) {
                            winners.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread w : workers) {
            w.join(10_000);
        }

        org.junit.Assert.assertEquals(1, winners.get());
        org.junit.Assert.assertEquals(MessageState.RECEIVED,
                repo.find("m-race", "c-1").get().getState());
    }

    // ---------------- seq 并发分配：无重复、无跳号 ----------------

    @Test
    public void seqAllocationConcurrentIsUniqueAndComplete() throws Exception {
        Assume.assumeTrue(enabled());
        final JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshMySql());
        final int threads = 4;
        final int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            List<Future<List<Long>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(new java.util.concurrent.Callable<List<Long>>() {
                    @Override
                    public List<Long> call() throws Exception {
                        start.await();
                        List<Long> allocated = new ArrayList<>();
                        for (int i = 0; i < perThread; i++) {
                            allocated.add(repo.nextDeliverySeq("c-1"));
                        }
                        return allocated;
                    }
                }));
            }
            start.countDown();
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> f : futures) {
                all.addAll(f.get(60, TimeUnit.SECONDS));
            }
            org.junit.Assert.assertEquals(threads * perThread, all.size());
            Collections.sort(all);
            for (int i = 0; i < all.size(); i++) {
                org.junit.Assert.assertEquals(i + 1, all.get(i).longValue());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------- 游标单调 + 注册表幂等 ----------------

    @Test
    public void cursorMonotonicAndRegistryIdempotent() throws Exception {
        Assume.assumeTrue(enabled());
        DataSource ds = freshMySql();
        JdbcClientCursorRepository cursor = new JdbcClientCursorRepository(ds);
        org.junit.Assert.assertFalse(cursor.getLastDeliveredSeq("c-1").isPresent());
        cursor.saveLastDeliveredSeq("c-1", 5);
        cursor.saveLastDeliveredSeq("c-1", 3); // 回退被拒绝
        org.junit.Assert.assertEquals(5, cursor.getLastDeliveredSeq("c-1").getAsLong());
        cursor.saveLastDeliveredSeq("c-1", 7);
        org.junit.Assert.assertEquals(7, cursor.getLastDeliveredSeq("c-1").getAsLong());

        JdbcClientRegistry registry = new JdbcClientRegistry(ds);
        registry.register(new ClientRegistration("store-mysql", true, NOW));
        registry.register(new ClientRegistration("store-mysql", false, NOW + 5)); // 幂等
        ClientRegistration loaded = registry.findByClientId("store-mysql").orElse(null);
        org.junit.Assert.assertEquals("store-mysql", loaded.getClientId());
        org.junit.Assert.assertTrue(loaded.isEnabled());
    }
}
