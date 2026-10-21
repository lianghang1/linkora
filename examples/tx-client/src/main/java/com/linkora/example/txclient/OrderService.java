package com.linkora.example.txclient;

import com.linkora.client.LinkoraClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 宿主业务服务：业务表写入与 {@code client.publish(...)} 在同一个宿主事务内完成。
 *
 * <p>placeOrder 的事务语义（由 TxClientConfig 的 Spring 事务管理器保证）：</p>
 * <ul>
 *   <li>正常返回 → 业务行与 outbox 记录一起提交，发送线程随后拾取上送；</li>
 *   <li>抛出异常 → 业务行与 outbox 记录一起回滚——消息"从未存在"。</li>
 * </ul>
 *
 * @author lianghang
 */
public class OrderService {

    private final JdbcTemplate businessJdbc;
    private final LinkoraClient client;

    /** 最近一次（含失败事务内）publish 生成的 messageId，供演示断言回滚效果。 */
    private volatile String lastMessageId;

    public OrderService(JdbcTemplate businessJdbc, LinkoraClient client) {
        this.businessJdbc = businessJdbc;
        this.client = client;
    }

    @Transactional
    public String placeOrder(String orderId, long amountCents, boolean failBeforeCommit) {
        businessJdbc.update("INSERT INTO business_order (order_id, amount_cents, created_at) "
                        + "VALUES (?, ?, ?)",
                orderId, amountCents, System.currentTimeMillis());
        // saveOutbox 经事务感知数据源加入当前事务：回滚时该记录随业务行一起消失
        String messageId = client.publish("order.created",
                orderId.getBytes(StandardCharsets.UTF_8));
        this.lastMessageId = messageId;
        if (failBeforeCommit) {
            throw new IllegalStateException("演示：业务校验失败，触发宿主事务回滚");
        }
        return messageId;
    }

    public String getLastMessageId() {
        return lastMessageId;
    }
}
