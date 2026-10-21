package com.linkora.example.txclient;

import com.linkora.client.LinkoraClient;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.testkit.EmbeddedLinkoraServer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 客户端事务接管示例（不使用 SQLite）：自实现 {@link H2ClientStore} 接 H2 宿主业务库，
 * outbox 与业务数据同事务。
 *
 * <p>演示两个场景（断言失败即退出码非零）：</p>
 * <ol>
 *   <li><b>提交</b>：业务行 + outbox 一起提交 → 发送线程拾取并上送，
 *       服务端确认后 outbox 进入 SERVER_ACCEPTED；</li>
 *   <li><b>回滚</b>：业务失败抛异常 → 业务行与 outbox 记录一起消失，
 *       消息"从未存在"，服务端永远收不到。</li>
 * </ol>
 *
 * <p>运行方式（仓库根目录，首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/tx-client exec:java ^
 *       -Dexec.mainClass=com.linkora.example.txclient.TxClientMain
 * </pre>
 *
 * @author lianghang
 */
public final class TxClientMain {

    private static final String CLIENT_ID = "tx-client-demo";

    private TxClientMain() {
    }

    public static void main(String[] args) throws Exception {
        // 1. 对端真实服务端（示例便利；生产为线上服务）
        EmbeddedLinkoraServer server = EmbeddedLinkoraServer.builder()
                .registerClient(CLIENT_ID).build();
        server.start();

        // 2. Spring 容器：H2 宿主库 + Spring 事务 + H2ClientStore + LinkoraClient
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(TxClientConfig.class, () -> new TxClientConfig(server.baseUrl()));
        context.refresh();

        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE TABLE IF NOT EXISTS business_order ("
                + "order_id     VARCHAR(64) PRIMARY KEY,"
                + "amount_cents BIGINT      NOT NULL,"
                + "created_at   BIGINT      NOT NULL)");

        OrderService orderService = context.getBean(OrderService.class);
        H2ClientStore store = context.getBean(H2ClientStore.class);

        LinkoraClient client = context.getBean(LinkoraClient.class);
        client.start();

        try {
            // ---------- 场景 A：提交 → 消息发出 ----------
            String msgA = orderService.placeOrder("ORD-COMMIT-1", 9900, false);
            boolean orderAExists = countOrder(jdbc, "ORD-COMMIT-1") == 1;
            assertState(orderAExists, "场景A：业务行应已提交");
            boolean acceptedA = awaitOutboxAccepted(store, msgA, 30_000);
            assertState(acceptedA, "场景A：outbox 应在提交后被发送线程送达服务端"
                    + "（当前状态: " + statusOf(store, msgA) + "）");
            System.out.println("[PASS] 场景A 提交：业务行已提交，消息 " + msgA
                    + " 已送达服务端（SERVER_ACCEPTED）");

            // ---------- 场景 B：回滚 → 消息从未存在 ----------
            boolean thrown = false;
            try {
                orderService.placeOrder("ORD-ROLLBACK-1", 100, true);
            } catch (IllegalStateException expected) {
                thrown = true;
            }
            assertState(thrown, "场景B：业务异常应向上抛出");
            String msgB = orderService.getLastMessageId();
            assertState(countOrder(jdbc, "ORD-ROLLBACK-1") == 0,
                    "场景B：业务行应已回滚");
            assertState(!store.findOutbox(msgB).isPresent(),
                    "场景B：outbox 记录应随宿主事务回滚消失（消息" + msgB + "从未存在）");
            System.out.println("[PASS] 场景B 回滚：业务行与 outbox 记录一起消失，"
                    + "消息 " + msgB + " 从未离开本机");

            System.out.println("========================================");
            System.out.println("客户端事务接管示例全部通过：outbox 与宿主业务同生共死。");
            System.out.println("========================================");
        } finally {
            client.close();
            context.close();
            server.close();
        }
    }

    private static int countOrder(JdbcTemplate jdbc, String orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_order WHERE order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static String statusOf(H2ClientStore store, String messageId) {
        OutboxEntry entry = store.findOutbox(messageId).orElse(null);
        return entry == null ? "<已消失>" : entry.getStatus() + "";
    }

    /** 轮询等待 outbox 条目被服务端确认（发送线程 → 服务端 → ACK 回流）。 */
    private static boolean awaitOutboxAccepted(H2ClientStore store,
                                               String messageId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            OutboxEntry entry = store.findOutbox(messageId).orElse(null);
            if (entry != null && entry.getStatus() == OutboxStatus.SERVER_ACCEPTED) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("断言失败: " + message);
        }
    }
}
