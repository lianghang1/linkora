package com.linkora.spring.boot;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.embedded.EmbeddedSqliteDatabase;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.spring.TxAwareDataSources;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断：事务内（@Transactional 语义）业务写 + 服务端仓储写，同时后台线程
 * 周期写（模拟 sweep），验证 TADSP 包装后事务内写是否走同一连接（无 SQLITE_BUSY）。
 *
 * @author lianghang
 */
public class SqliteTxDiagnosisTest {

    @Test
    public void txInnerServerWriteWithBackgroundWriter() throws Exception {
        EmbeddedSqliteDatabase db = EmbeddedSqliteDatabase.atFile(
                "target/diag-" + System.currentTimeMillis() + ".db");
        DataSource storage = TxAwareDataSources.wrap(db.dataSource());
        JdbcServerSchemaBridge.migrate(storage);

        PlatformTransactionManager tm = new DataSourceTransactionManager(storage);
        JdbcTemplate jdbc = new JdbcTemplate(storage);
        jdbc.execute("CREATE TABLE IF NOT EXISTS business_order ("
                + " order_no VARCHAR(64) PRIMARY KEY, created_at BIGINT)");

        JdbcMessageRepository messages = new JdbcMessageRepository(storage);
        JdbcDeliveryRepository deliveries = new JdbcDeliveryRepository(storage);

        // 模拟 server 后台 sweep：每 500ms 写一次 lk_delivery
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread sweeper = new Thread(() -> {
            while (!stop.get()) {
                try (java.sql.Connection c = storage.getConnection();
                     java.sql.Statement st = c.createStatement()) {
                    long now = System.currentTimeMillis();
                    st.execute("INSERT OR REPLACE INTO lk_delivery (message_id, client_id, seq, "
                            + "state, retry_count, last_error, created_at, updated_at) VALUES ('"
                            + "sweep-" + now + "', 'sweep', 1, 'PROCESSED', 0, NULL, " + now + ", " + now + ")");
                } catch (Exception e) {
                    System.out.println("[sweep] " + e.getMessage());
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
        });
        sweeper.start();

        // 事务内：业务写 + 仓储写（模拟 placeOrderAtomically）
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(true);
        Thread txThread = new Thread(() -> {
            try {
                new TransactionTemplate(tm).execute(status -> {
                    jdbc.update("INSERT INTO business_order VALUES (?, ?)",
                            "ATOM-1", System.currentTimeMillis());
                    long now = System.currentTimeMillis();
                    LinkoraMessage message = LinkoraMessage.builder()
                            .messageId("diag-1").clientId("c-1").messageType("t")
                            .payload(new byte[]{1}).createdAt(now).build();
                    messages.save(message);
                    deliveries.init(new DeliveryRecord("diag-1", "c-1", 1,
                            MessageState.ACCEPTED, 0, null, null, now, now));
                    return null;
                });
            } catch (Exception e) {
                ok.set(false);
                System.out.println("[tx] FAIL: " + e.getMessage());
                Throwable root = e;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                System.out.println("[tx] root: " + root.getMessage());
            } finally {
                done.countDown();
            }
        });
        txThread.start();
        done.await();
        stop.set(true);
        sweeper.join();

        System.out.println("[result] ok=" + ok.get());
        assertTrue(ok.get(), "事务内写失败");
        db.close();
    }

    /** 桥接：避免诊断测试直接依赖 server 包内可见性问题（同为 classpath 依赖）。 */
    static final class JdbcServerSchemaBridge {
        static void migrate(DataSource ds) {
            com.linkora.server.storage.jdbc.JdbcServerSchema.migrate(ds);
        }
    }
}
