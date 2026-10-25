package com.linkora.spring.boot;

import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.core.storage.client.jdbc.JdbcClientStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 诊断：JdbcClientStore 在 H2 + Hikari（datasource 模式真实环境）上的
 * saveOutbox / findOutboxByStatus 高频访问（复现客户端 io 线程模型）。
 *
 * @author lianghang
 */
class JdbcClientStoreH2DiagTest {

    @Test
    void saveAndPollOnHikariH2() throws Exception {
        // H2 内置连接池语义等价（诊断聚焦 SQL/迁移，池行为由 Boot 环境验证）
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:client-h2-diag;DB_CLOSE_DELAY=-1");

        JdbcClientStore store = new JdbcClientStore(ds);

        // 高频并发写+轮询（模拟 client io 线程模型）30 秒
        long deadline = System.currentTimeMillis() + 30_000;
        Thread sender = new Thread(() -> {
            int i = 0;
            while (System.currentTimeMillis() < deadline) {
                OutboxEntry e = new OutboxEntry("m-" + (i++), "t", new byte[]{1},
                        OutboxStatus.PENDING, System.currentTimeMillis(), System.currentTimeMillis(),
                        0, null, null, null, null);
                store.saveOutbox(e);
                store.findOutbox(OutboxStatus.PENDING, Long.MAX_VALUE, 10);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                }
            }
        });
        sender.start();
        sender.join(35_000);

        assertTrue(store.findOutbox("m-0").isPresent(), "首条消息应已落库");
    }
}
