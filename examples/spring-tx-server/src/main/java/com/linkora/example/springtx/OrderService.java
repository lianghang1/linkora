package com.linkora.example.springtx;

import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

/**
 * 宿主业务服务：业务表写入与 {@code server.publish(...)} 在同一个宿主事务内完成。
 *
 * <p>placeOrder 的事务语义（由 TxServerConfig 的 Spring 事务管理器保证）：</p>
 * <ul>
 *   <li>正常返回 → 业务行与消息/投递记录一起提交，随后按常规流程推送；</li>
 *   <li>抛出异常 → 业务行、消息行、投递记录一起回滚——
 *       对客户端而言消息"从未存在"，这正是事务性发件箱（transactional outbox）。</li>
 * </ul>
 *
 * @author lianghang
 */
public class OrderService {

    private final JdbcTemplate businessJdbc;
    private final LinkoraServer server;

    /** 最近一次（含失败事务内）publish 生成的 messageId，供演示断言回滚效果。 */
    private volatile String lastMessageId;

    public OrderService(JdbcTemplate businessJdbc, LinkoraServer server) {
        this.businessJdbc = businessJdbc;
        this.server = server;
    }

    @Transactional
    public String placeOrder(String orderId, long amountCents, boolean failBeforeCommit) {
        businessJdbc.update("INSERT INTO business_order (order_id, amount_cents, created_at) "
                        + "VALUES (?, ?, ?)",
                orderId, amountCents, System.currentTimeMillis());
        // 持久化经事务感知数据源加入当前事务：回滚时消息随业务行一起消失
        PublishResult result = server.publish(TxServerConfig.DEMO_CLIENT_ID, "order.created",
                orderId.getBytes(StandardCharsets.UTF_8));
        this.lastMessageId = result.getMessageId();
        if (failBeforeCommit) {
            throw new IllegalStateException("演示：业务校验失败，触发宿主事务回滚");
        }
        return result.getMessageId();
    }

    public String getLastMessageId() {
        return lastMessageId;
    }
}
