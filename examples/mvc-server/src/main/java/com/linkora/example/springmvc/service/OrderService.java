package com.linkora.example.springmvc.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import com.linkora.serialize.jackson.LinkoraJsonUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 订单服务（线上侧）：用户下单后经 {@link LinkoraServer} 通知线下门店。
 * payload 为 JSON：{@code {"orderNo":"SO-1001","item":"机械键盘 x1"}}。
 *
 * @author lianghang
 */
@Service
public class OrderService {

    /** 下行 topic：新订单通知（线下门店按此类型监听）。 */
    public static final String TOPIC_ORDER_CREATED = "order-created";

    private final LinkoraServer linkoraServer;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public OrderService(LinkoraServer linkoraServer,
                        @org.springframework.beans.factory.annotation.Qualifier("linkoraDataSource") javax.sql.DataSource storageDataSource) {
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(storageDataSource);
        this.jdbc.execute("CREATE TABLE IF NOT EXISTS business_order ("
                + " order_no    VARCHAR(64) PRIMARY KEY,"
                + " item        VARCHAR(255),"
                + " created_at  BIGINT)");
        this.linkoraServer = linkoraServer;
    }

    /**
     * 线上下单并通知线下门店：先持久化（门店离线则上线补投），在线时经 SSE 实时送达。
     */
    public PublishResult placeOrder(String clientId, String orderNo, String item) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("item", item);
        // sequenceKey = orderNo：同一订单的后续通知严格按发布顺序送达
        return linkoraServer.publish(clientId, TOPIC_ORDER_CREATED,
                payload.toString().getBytes(StandardCharsets.UTF_8), orderNo);
    }

    /**
     * 同库同事务演示（datasource 模式）：业务行与消息一起提交 / 一起消失。
     * mysql 模式下业务表与 lk_* 表同库，@Transactional 同时包住两者。
     */
    @org.springframework.transaction.annotation.Transactional
    public PublishResult placeOrderAtomically(String clientId, String orderNo,
                                              String item, boolean failAfterPublish) {
        jdbc.update("INSERT INTO business_order (order_no, item, created_at) VALUES (?, ?, ?)",
                orderNo, item, System.currentTimeMillis());
        PublishResult result = placeOrder(clientId, orderNo, item);
        if (failAfterPublish) {
            throw new IllegalStateException(
                    "演示：publish 之后业务回滚——业务行与消息一起消失");
        }
        return result;
    }

    /** 已落库订单（验证回滚效果用）。 */
    public boolean orderExists(String orderNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_order WHERE order_no = ?",
                Integer.class, orderNo);
        return count != null && count > 0;
    }

    /** 无 sequenceKey 的下行通知（对照演示用）：与带键消息混排时不受任何门禁影响。 */
    public PublishResult placeOrderNoKey(String clientId, String orderNo, String item) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("item", item);
        return linkoraServer.publish(clientId, TOPIC_ORDER_CREATED,
                payload.toString().getBytes(StandardCharsets.UTF_8));
    }
}
