package com.linkora.server.storage.jdbc;

import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.DeliveryRecord;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JDBC 服务端存储测试（H2）：去重、CAS 竞争、seq 原子分配、补发查询、故障注入。
 *
 * @author lianghang
 */
public class JdbcServerStorageTest {

    private static final long NOW = System.currentTimeMillis();

    /** 每个测试独立命名的内存库，避免用例间串扰。 */
    static DataSource freshH2(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        JdbcServerSchema.migrate(ds);
        return ds;
    }

    private static DeliveryRecord record(String messageId, String clientId, long seq, MessageState state) {
        return new DeliveryRecord(messageId, clientId, seq, state, 0, null, null, NOW, NOW);
    }

    // ---------------- schema 迁移 ----------------

    @Test
    public void migrationIsIdempotent() {
        DataSource ds = freshH2("schema-idem");
        // 重复迁移不报错
        JdbcServerSchema.migrate(ds);
        JdbcServerSchema.migrate(ds);
        // 版本记录唯一且为最新
        // 通过 JdbcMessageRepository 的行为间接验证表存在
        new JdbcMessageRepository(ds).findByMessageId("none").ifPresent(m -> {
            throw new AssertionError("空库不应有消息");
        });
    }

    // ---------------- MessageRepository ----------------

    @Test
    public void messageSaveFindRoundtrip() {
        JdbcMessageRepository repo = new JdbcMessageRepository(freshH2("msg-roundtrip"));
        com.linkora.core.message.LinkoraMessage original =
                com.linkora.core.message.LinkoraMessage.builder()
                        .messageId("msg-1")
                        .clientId("store-001")
                        .messageType("ORDER_CREATED")
                        .payload(new byte[]{1, 2, 3, -5, 127, -128})
                        .createdAt(NOW)
                        .expiresAt(null)
                        .schemaVersion(2)
                        .traceId("trace-x")
                        .build();
        repo.save(original);
        com.linkora.core.message.LinkoraMessage loaded = repo.findByMessageId("msg-1").orElse(null);
        assertEquals(original, loaded);
    }

    @Test
    public void messageDuplicateSaveKeepsOriginal() {
        JdbcMessageRepository repo = new JdbcMessageRepository(freshH2("msg-dedup"));
        com.linkora.core.message.LinkoraMessage original =
                com.linkora.core.message.LinkoraMessage.builder()
                        .messageId("msg-dup")
                        .clientId("store-001")
                        .messageType("ORDER_CREATED")
                        .payload("first".getBytes())
                        .createdAt(NOW)
                        .build();
        repo.save(original);
        // 重复保存（不同内容）：静默忽略，保留原记录
        repo.save(com.linkora.core.message.LinkoraMessage.builder()
                .messageId("msg-dup")
                .clientId("store-001")
                .messageType("OTHER")
                .payload("second".getBytes())
                .createdAt(NOW + 1)
                .build());
        com.linkora.core.message.LinkoraMessage loaded = repo.findByMessageId("msg-dup").orElse(null);
        assertEquals("ORDER_CREATED", loaded.getMessageType());
        assertEquals("first", loaded.getPayloadAsUtf8String());
    }

    @Test
    public void messageFindMissingReturnsEmpty() {
        JdbcMessageRepository repo = new JdbcMessageRepository(freshH2("msg-missing"));
        assertFalse(repo.findByMessageId("nope").isPresent());
    }

    @Test
    public void storageFailureIsWrappedAsLinkoraException() {
        JdbcMessageRepository repo = new JdbcMessageRepository(new FailingDataSource());
        try {
            repo.save(com.linkora.core.message.LinkoraMessage.builder()
                    .messageId("msg-fail")
                    .clientId("c")
                    .messageType("t")
                    .payload(new byte[0])
                    .createdAt(NOW)
                    .build());
            throw new AssertionError("故障注入下应抛出异常");
        } catch (com.linkora.core.error.LinkoraException e) {
            assertEquals(com.linkora.core.error.LinkoraErrorCode.STORAGE_ERROR, e.getErrorCode());
        }
    }

    // ---------------- DeliveryRepository：CAS ----------------

    @Test
    public void deliveryInitFindRoundtripAndDuplicateInit() {
        JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshH2("dlv-init"));
        repo.init(record("m-1", "c-1", 1, MessageState.CREATED));
        // 重复初始化：幂等忽略，保留原记录
        repo.init(record("m-1", "c-1", 99, MessageState.PROCESSED));
        DeliveryRecord loaded = repo.find("m-1", "c-1").orElse(null);
        assertEquals(1, loaded.getSeq());
        assertEquals(MessageState.CREATED, loaded.getState());
        assertFalse(repo.find("m-1", "c-other").isPresent());
    }

    @Test
    public void deliveryCasTransitionSemantics() {
        JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshH2("dlv-cas"));
        repo.init(record("m-2", "c-1", 1, MessageState.CREATED));

        assertTrue(repo.compareAndSetState("m-2", "c-1", MessageState.CREATED,
                MessageState.RECEIVED, null, null, 0, 0));
        // 旧期望重复 CAS（如迟到的重复 ACK）：失败，状态不回退
        assertFalse(repo.compareAndSetState("m-2", "c-1", MessageState.CREATED,
                MessageState.RECEIVED, null, null, 0, 0));
        assertTrue(repo.compareAndSetState("m-2", "c-1", MessageState.RECEIVED,
                MessageState.PROCESSED, null, null, 0, 0));
        assertEquals(MessageState.PROCESSED, repo.find("m-2", "c-1").get().getState());
    }

    @Test
    public void deliveryCasConcurrentHasExactlyOneWinner() throws Exception {
        JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshH2("dlv-race"));
        repo.init(record("m-race", "c-1", 1, MessageState.CREATED));

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger winners = new AtomicInteger();
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

        assertEquals(1, winners.get());
        assertEquals(MessageState.RECEIVED, repo.find("m-race", "c-1").get().getState());
    }

    // ---------------- DeliveryRepository：seq 分配 ----------------

    @Test
    public void seqAllocationIsSequential() {
        JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshH2("seq-plain"));
        for (int i = 1; i <= 5; i++) {
            assertEquals(i, repo.nextDeliverySeq("c-a"));
        }
        // 不同客户端序号独立
        assertEquals(1, repo.nextDeliverySeq("c-b"));
    }

    @Test
    public void seqAllocationConcurrentIsUniqueAndComplete() throws Exception {
        final JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshH2("seq-race"));
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
                all.addAll(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(threads * perThread, all.size());
            Collections.sort(all);
            // 无重复、无跳号：并发分配结果恰好为 1..N
            for (int i = 0; i < all.size(); i++) {
                assertEquals(i + 1, all.get(i).longValue());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 宿主事务接管回归测试：连接已处于手动提交模式（autoCommit=false）时，
     * seq 分配必须加入当前事务——不得中途 commit（会提前提交宿主业务写入），
     * 宿主回滚后分配结果应随之消失。独立调用（自动提交连接）行为不变。
     */
    @Test
    public void seqAllocationJoinsAmbientManualCommitTransaction() throws Exception {
        DataSource ds = freshH2("seq-ambient");
        JdbcDeliveryRepository standaloneRepo = new JdbcDeliveryRepository(ds);

        // 模拟宿主事务：一条绑定的手动提交连接，close 被宿主抑制
        final Connection ambient = ds.getConnection();
        ambient.setAutoCommit(false);
        Connection shared = (Connection) java.lang.reflect.Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        return null; // 宿主持有连接，仓储不得真正关闭
                    }
                    try {
                        return method.invoke(ambient, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        DataSource ambientDs = new DataSource() {
            @Override public Connection getConnection() { return shared; }
            @Override public Connection getConnection(String u, String p) { return shared; }
            @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) { }
            @Override public void setLoginTimeout(int seconds) { }
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger()
                    throws java.sql.SQLFeatureNotSupportedException {
                throw new java.sql.SQLFeatureNotSupportedException();
            }
        };
        JdbcDeliveryRepository ambientRepo = new JdbcDeliveryRepository(ambientDs);

        assertEquals(1, ambientRepo.nextDeliverySeq("c-1"));

        // 宿主事务未提交：其他连接不可见（若仓储中途 commit 会破坏这一点）
        Connection observer = ds.getConnection();
        try (ResultSet rs = observer.prepareStatement(
                "SELECT last_seq FROM lk_client_seq WHERE client_id = 'c-1'").executeQuery()) {
            assertFalse("seq 分配不应在宿主事务内提前提交", rs.next());
        }
        // 宿主回滚 → 分配结果消失
        ambient.rollback();
        try (ResultSet rs = observer.prepareStatement(
                "SELECT last_seq FROM lk_client_seq WHERE client_id = 'c-1'").executeQuery()) {
            assertFalse("宿主回滚后 seq 分配应随事务消失", rs.next());
        }

        // 独立调用（自动提交连接）行为不变：本地事务自行提交、立即可见
        assertEquals(1, standaloneRepo.nextDeliverySeq("c-1"));
        try (ResultSet rs = observer.prepareStatement(
                "SELECT last_seq FROM lk_client_seq WHERE client_id = 'c-1'").executeQuery()) {
            assertTrue("独立调用应立即提交", rs.next());
            assertEquals(1, rs.getLong(1));
        }

        observer.close();
        ambient.close();
    }

    // ---------------- DeliveryRepository：补发查询 ----------------

    @Test
    public void unfinishedQueryFiltersTerminalAndHonorsSeq() {
        JdbcDeliveryRepository repo = new JdbcDeliveryRepository(freshH2("dlv-unfinished"));
        repo.init(record("m-1", "c-1", 1, MessageState.PROCESSED));
        repo.init(record("m-2", "c-1", 2, MessageState.CREATED));
        repo.init(record("m-3", "c-1", 3, MessageState.RETRYING));
        repo.init(record("m-4", "c-1", 4, MessageState.DEAD_LETTER)); // 非终态，需补发
        repo.init(record("m-5", "c-1", 5, MessageState.EXPIRED));
        repo.init(record("m-6", "c-2", 1, MessageState.CREATED)); // 其他客户端隔离

        List<DeliveryRecord> all = repo.findUnfinishedAfterSeq("c-1", 0, 100);
        assertEquals(3, all.size());
        assertEquals("m-2", all.get(0).getMessageId());
        assertEquals("m-3", all.get(1).getMessageId());
        assertEquals("m-4", all.get(2).getMessageId());

        List<DeliveryRecord> afterSeq2 = repo.findUnfinishedAfterSeq("c-1", 2, 100);
        assertEquals(2, afterSeq2.size());
        assertEquals("m-3", afterSeq2.get(0).getMessageId());
        assertEquals("m-4", afterSeq2.get(1).getMessageId());

        List<DeliveryRecord> limited = repo.findUnfinishedAfterSeq("c-1", 0, 2);
        assertEquals(2, limited.size());
    }

    // ---------------- ClientRegistry ----------------

    @Test
    public void registryRegisterFindAndUpdateState() throws Exception {
        DataSource ds = freshH2("registry-basic");
        JdbcClientRegistry registry = new JdbcClientRegistry(ds);
        assertFalse(registry.findByClientId("store-001").isPresent());

        registry.register(new com.linkora.core.storage.server.ClientRegistration("store-001", true, NOW));
        com.linkora.core.storage.server.ClientRegistration loaded =
                registry.findByClientId("store-001").orElse(null);
        assertEquals("store-001", loaded.getClientId());
        assertTrue(loaded.isEnabled());
        assertEquals(NOW, loaded.getCreatedAt());

        // 重复注册幂等：保留首次记录
        registry.register(new com.linkora.core.storage.server.ClientRegistration("store-001", false, NOW + 5));
        loaded = registry.findByClientId("store-001").orElse(null);
        assertTrue(loaded.isEnabled());
        assertEquals(NOW, loaded.getCreatedAt());

        // 连接状态更新（通过直查表验证，ClientRegistration 不承载该字段）
        registry.updateConnectionState("store-001", com.linkora.core.storage.server.ConnectionState.CONNECTED, NOW + 1);
        try (java.sql.Connection c = ds.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement(
                     "SELECT connection_state, last_active_at FROM lk_client WHERE client_id = ?")) {
            ps.setString(1, "store-001");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("CONNECTED", rs.getString(1));
                assertEquals(NOW + 1, rs.getLong(2));
            }
        }
        // 未注册客户端更新连接状态：不报错（尽力而为语义）
        registry.updateConnectionState("ghost", com.linkora.core.storage.server.ConnectionState.CONNECTED, NOW);
    }

    // ---------------- ClientCursorRepository ----------------

    @Test
    public void cursorMonotonicAdvance() {
        JdbcClientCursorRepository repo = new JdbcClientCursorRepository(freshH2("cursor-basic"));
        assertFalse(repo.getLastDeliveredSeq("c-1").isPresent());

        repo.saveLastDeliveredSeq("c-1", 5);
        assertEquals(5, repo.getLastDeliveredSeq("c-1").getAsLong());

        // 回退写被拒绝（单调前进）
        repo.saveLastDeliveredSeq("c-1", 3);
        assertEquals(5, repo.getLastDeliveredSeq("c-1").getAsLong());

        // 前进成功
        repo.saveLastDeliveredSeq("c-1", 7);
        assertEquals(7, repo.getLastDeliveredSeq("c-1").getAsLong());

        // 客户端隔离
        assertFalse(repo.getLastDeliveredSeq("c-2").isPresent());
    }
}
