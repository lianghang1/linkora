package com.linkora.example.springtx;

import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.spring.LinkoraServerConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 服务端宿主事务接管示例：publish 的持久化与宿主业务写入同生共死。
 *
 * <p>本示例不开放 HTTP 端点（连接与推送语义见其他示例），聚焦存储层事务语义，
 * 通过两个场景断言验证（失败即退出码非零）：</p>
 * <ol>
 *   <li><b>提交</b>：业务行、消息行、投递记录一起可见；</li>
 *   <li><b>回滚</b>：三者一起消失——消息"从未存在"，客户端永远收不到。</li>
 * </ol>
 *
 * <p>运行方式（仓库根目录，首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/spring-tx-server exec:java ^
 *       -Dexec.mainClass=com.linkora.example.springtx.SpringTxServerMain
 * </pre>
 */
public final class SpringTxServerMain {

    private SpringTxServerMain() {
    }

    public static void main(String[] args) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                TxServerConfig.class, LinkoraServerConfiguration.class);

        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE TABLE IF NOT EXISTS business_order ("
                + "order_id     VARCHAR(64) PRIMARY KEY,"
                + "amount_cents BIGINT      NOT NULL,"
                + "created_at   BIGINT      NOT NULL)");

        // 注册演示客户端（幂等；客户端离线即可完成全部断言）
        ClientRegistry registry = context.getBean(ClientRegistry.class);
        if (!registry.findByClientId(TxServerConfig.DEMO_CLIENT_ID).isPresent()) {
            registry.register(new ClientRegistration(
                    TxServerConfig.DEMO_CLIENT_ID, true, System.currentTimeMillis()));
        }

        OrderService orderService = context.getBean(OrderService.class);
        MessageRepository messageRepository = context.getBean(MessageRepository.class);
        DeliveryRepository deliveryRepository = context.getBean(DeliveryRepository.class);

        try {
            // ---------- 场景 A：提交 → 消息与业务行一起可见 ----------
            String msgA = orderService.placeOrder("ORD-COMMIT-1", 9900, false);
            assertState(countOrder(jdbc, "ORD-COMMIT-1") == 1, "场景A：业务行应已提交");
            assertState(messageRepository.findByMessageId(msgA).isPresent(),
                    "场景A：消息应已持久化");
            assertState(deliveryRepository.find(msgA, TxServerConfig.DEMO_CLIENT_ID).isPresent(),
                    "场景A：投递记录应已建立（客户端上线后即可补发）");
            System.out.println("[PASS] 场景A 提交：业务行 + 消息 + 投递记录一起可见（"
                    + msgA + "）");

            // ---------- 场景 B：回滚 → 消息从未存在 ----------
            boolean thrown = false;
            try {
                orderService.placeOrder("ORD-ROLLBACK-1", 100, true);
            } catch (IllegalStateException expected) {
                thrown = true;
            }
            assertState(thrown, "场景B：业务异常应向上抛出");
            String msgB = orderService.getLastMessageId();
            assertState(countOrder(jdbc, "ORD-ROLLBACK-1") == 0, "场景B：业务行应已回滚");
            assertState(!messageRepository.findByMessageId(msgB).isPresent(),
                    "场景B：消息应随宿主事务回滚消失");
            assertState(!deliveryRepository.find(msgB, TxServerConfig.DEMO_CLIENT_ID).isPresent(),
                    "场景B：投递记录应随宿主事务回滚消失");
            System.out.println("[PASS] 场景B 回滚：业务行 + 消息 + 投递记录一起消失，"
                    + "消息 " + msgB + " 从未存在");

            System.out.println("========================================");
            System.out.println("服务端事务接管示例全部通过：publish 持久化与宿主业务同生共死。");
            System.out.println("========================================");
        } finally {
            context.close();
        }
    }

    private static int countOrder(JdbcTemplate jdbc, String orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_order WHERE order_id = ?",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private static void assertState(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("断言失败: " + message);
        }
    }
}
