package com.linkora.example.boot.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.serialize.jackson.LinkoraJsonUtil;
import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

/**
 * 订单服务（线上侧）：用户下单后经 {@link LinkoraServer} 通知线下门店。
 * payload 为 JSON：{@code {"orderNo":"SO-1001","item":"机械键盘 x1"}}。
 *
 * <p>{@code placeOrderAtomically} 演示 0.5.0 的同库同事务能力：
 * 业务表写入与 {@code server.publish(...)} 在同一 {@code @Transactional} 内——
 * 业务回滚时消息"从未存在"；datasource 模式下业务表与 lk_* 表同库，
 * sqlite 模式下业务表建在内嵌库中，两种模式语义一致。</p>
 *
 * @author lianghang
 */
@Service
public class OrderService {

    /** 下行 topic：新订单通知（线下门店按此类型监听）。 */
    public static final String TOPIC_ORDER_CREATED = "order-created";

    private final LinkoraServer linkoraServer;
    private final JdbcTemplate jdbc;

    public OrderService(LinkoraServer linkoraServer,
                                @org.springframework.beans.factory.annotation.Qualifier("linkoraServerStorage") DataSource storageDataSource) {
        this.linkoraServer = linkoraServer;
        // storageDataSource = starter 暴露的 linkoraServerStorage：
        // datasource 模式为宿主业务库（事务感知），sqlite 模式为内嵌 SQLite
        this.jdbc = new JdbcTemplate(storageDataSource);
        this.jdbc.execute("CREATE TABLE IF NOT EXISTS business_order ("
                + " order_no    VARCHAR(64) PRIMARY KEY,"
                + " item        VARCHAR(255),"
                + " created_at  BIGINT)");
    }

    /**
     * 线上下单并通知线下门店：先持久化（门店离线则上线补投），在线时经 SSE 实时送达。
     * sequenceKey = orderNo：同一订单的后续通知严格按发布顺序送达，
     * 重试/补发不会让"订单已发货"越过"订单已创建"先到。
     */
    public PublishResult placeOrder(String clientId, String orderNo, String item) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("item", item);
        return linkoraServer.publish(clientId, TOPIC_ORDER_CREATED,
                payload.toString().getBytes(StandardCharsets.UTF_8), orderNo);
    }

    /**
     * 无 sequenceKey 的下行通知（对照演示用）：与带键消息混排时不受任何门禁影响。
     */
    public PublishResult placeOrderNoKey(String clientId, String orderNo, String item) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("item", item);
        return linkoraServer.publish(clientId, TOPIC_ORDER_CREATED,
                payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 同库同事务演示：业务行与消息一起提交 / 一起消失。
     *
     * @param failAfterPublish true 时在 publish 之后抛异常，触发整体回滚——
     *                         消息"从未存在"，可用 GET /api/demo/orders 查证
     */
    @Transactional
    public PublishResult placeOrderAtomically(String clientId, String orderNo,
                                              String item, boolean failAfterPublish) {
        jdbc.update("INSERT INTO business_order (order_no, item, created_at) VALUES (?, ?, ?)",
                orderNo, item, System.currentTimeMillis());
        PublishResult result = placeOrder(clientId, orderNo, item);
        if (failAfterPublish) {
            throw new IllegalStateException(
                    "演示：publish 之后业务回滚——业务行与消息一起消失（messageId=" + result.getMessageId() + "）");
        }
        return result;
    }

    /** 已落库订单号（验证回滚效果用：回滚后应查不到对应订单）。 */
    public boolean orderExists(String orderNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_order WHERE order_no = ?",
                Integer.class, orderNo);
        return count != null && count > 0;
    }
}
