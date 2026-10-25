package com.linkora.server.storage.jdbc;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.embedded.EmbeddedSqliteDatabase;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.DeliveryRecord;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 服务端存储 SQLite 冒烟测试（store=sqlite 内嵌模式）：
 * schema 迁移（含 v2 no-op、v3/v4 ADD COLUMN 容错）+ 四仓储基本读写 +
 * 重复迁移幂等。全量行为矩阵（CAS 竞争/补发/故障注入）由
 * {@code JdbcServerStorageTest}（H2）与 {@code JdbcServerStorageMySqlIT} 覆盖，
 * SQL 方言中立，此处验证 SQLite 引擎上的关键通路。
 *
 * @author lianghang
 */
public class JdbcServerStorageSqliteTest {

    private Path dbFile;
    private EmbeddedSqliteDatabase db;
    private DataSource ds;

    @Before
    public void setUp() throws Exception {
        dbFile = Files.createTempDirectory("linkora-server-sqlite-").resolve("server.db");
        db = EmbeddedSqliteDatabase.atFile(dbFile.toString());
        ds = db.dataSource();
        JdbcServerSchema.migrate(ds);
    }

    @After
    public void tearDown() {
        db.close();
    }

    @Test
    public void migrateIsIdempotentOnSqlite() {
        // 重跑迁移：v3/v4 的 ADD COLUMN（无 IF NOT EXISTS）由 duplicate column 容错命中
        JdbcServerSchema.migrate(ds);
        JdbcServerSchema.migrate(ds);
        assertEquals(JdbcServerSchema.LATEST_VERSION, currentVersion());
    }

    @Test
    public void messageAndDeliveryRoundTrip() {
        JdbcMessageRepository messages = new JdbcMessageRepository(ds);
        JdbcDeliveryRepository deliveries = new JdbcDeliveryRepository(ds);
        long now = System.currentTimeMillis();

        LinkoraMessage message = LinkoraMessage.builder()
                .messageId("msg-sq-1").clientId("c-1").messageType("order.created")
                .payload(new byte[]{1, 2, 3}).createdAt(now)
                .traceId("trace-1").sequenceKey("key-a").subject("订单主题")
                .build();
        assertTrue(messages.save(message));

        deliveries.init(new DeliveryRecord(
                "msg-sq-1", "c-1", 1, MessageState.ACCEPTED, 0, null, null, now, now));

        assertTrue(deliveries.compareAndSetState(
                "msg-sq-1", "c-1", MessageState.ACCEPTED, MessageState.RECEIVED,
                null, null, 0, 0));

        List<DeliveryRecord> all = deliveries.findAllByMessageId("msg-sq-1");
        assertEquals(1, all.size());
        assertEquals(MessageState.RECEIVED, all.get(0).getState());
    }

    @Test
    public void registryAndCursorRoundTrip() {
        JdbcClientRegistry registry = new JdbcClientRegistry(ds);
        ClientCursorRepository cursor = new JdbcClientCursorRepository(ds);
        long now = System.currentTimeMillis();

        registry.register(new ClientRegistration("c-1", true, now, "门店001"));
        ClientRegistration found = registry.findByClientId("c-1").get();
        assertEquals("门店001", found.getDisplayName());

        cursor.saveLastDeliveredSeq("c-1", 5);
        cursor.saveLastDeliveredSeq("c-1", 3);   // 滞后写入：单调性保持
        assertEquals(5L, cursor.getLastDeliveredSeq("c-1").getAsLong());
    }

    private int currentVersion() {
        try (java.sql.Connection connection = ds.getConnection();
             java.sql.Statement st = connection.createStatement();
             java.sql.ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM lk_schema_version")) {
            rs.next();
            return rs.getInt(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
