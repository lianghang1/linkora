package com.linkora.example.bootclient.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.client.UplinkPublisher;
import com.linkora.serialize.jackson.LinkoraJsonUtil;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

/**
 * 订单状态服务（线下门店侧）：门店更改订单状态后调用线上。
 * payload 为 JSON：{@code {"orderNo":"SO-1001","status":"SHIPPED"}}，
 * 线上由 OrderStatusHandler 处理；发布/确认能力由 SDK 的
 * {@link UplinkPublisher}（客户端装配自动注册的 bean）提供。
 *
 * <p>{@code updateStatusAtomically} 演示 0.5.0 的同库同事务能力
 * （transactional outbox）：datasource 模式下业务行与 lk_client_outbox
 * 队列写入同库，业务回滚时消息"从未存在"；sqlite 模式下队列在本地
 * SQLite 文件，publish 仍正常（业务行写内嵌对照库，无同事务语义）。</p>
 *
 * @author lianghang
 */
@Service
public class OrderStatusService {

    /** 上行 topic：与线上 OrderStatusHandler.messageType() 一致。 */
    public static final String TOPIC = "order-status-updated";

    private final UplinkPublisher publisher;
    private final JdbcTemplate jdbc;   /* datasource 模式才有；sqlite 模式为 null */

    public OrderStatusService(UplinkPublisher publisher,
                              org.springframework.beans.factory.ObjectProvider<DataSource> storeDataSource) {
        this.publisher = publisher;
        // storeDataSource = starter 装配的客户端存储数据源：
        // datasource 模式为宿主业务库（事务感知），sqlite 模式无此 Bean
        DataSource ds = storeDataSource.getIfAvailable();
        if (ds != null) {
            this.jdbc = new JdbcTemplate(ds);
            this.jdbc.execute("CREATE TABLE IF NOT EXISTS business_shipment ("
                    + " order_no    VARCHAR(64) PRIMARY KEY,"
                    + " status      VARCHAR(32),"
                    + " updated_at  BIGINT)");
        } else {
            this.jdbc = null;
        }
    }

    /**
     * 更新订单状态并通知线上，返回消息 ID。
     * sequenceKey = orderNo：同一订单的状态变更严格按发布顺序被线上接收，
     * 不会出现"已签收"先于"已发货"到达导致状态回退。
     */
    public String updateStatus(String orderNo, String status) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("status", status);
        return publisher.publish(TOPIC, payload, orderNo);
    }

    /**
     * 同库同事务演示（仅 datasource 模式）：业务行与 outbox 写入一起提交 / 一起消失。
     *
     * @param failAfterPublish true 时在 publish 之后抛异常触发整体回滚
     */
    @Transactional
    public String updateStatusAtomically(String orderNo, String status, boolean failAfterPublish) {
        requireJdbc();
        jdbc.update("INSERT INTO business_shipment (order_no, status, updated_at) VALUES (?, ?, ?)",
                orderNo, status, System.currentTimeMillis());
        String messageId = updateStatus(orderNo, status);
        if (failAfterPublish) {
            throw new IllegalStateException(
                    "演示：publish 之后业务回滚——业务行与消息一起消失（messageId=" + messageId + "）");
        }
        return messageId;
    }

    /** 已落库发货记录（验证回滚效果用）。 */
    public boolean shipmentExists(String orderNo) {
        requireJdbc();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM business_shipment WHERE order_no = ?",
                Integer.class, orderNo);
        return count != null && count > 0;
    }

    /** 同事务演示是否可用（仅 datasource 模式）。 */
    public boolean isTxAvailable() {
        return jdbc != null;
    }

    private void requireJdbc() {
        if (jdbc == null) {
            throw new IllegalStateException(
                    "同事务演示仅 datasource 模式可用（--spring.profiles.active=datasource，"
                            + "sqlite 模式下本地队列与业务库不在同一事务域）");
        }
    }

    /** 等待该消息被服务端确认（转发 {@link UplinkPublisher#awaitServerAccepted}）。 */
    public boolean awaitServerAccepted(String messageId, long timeoutMs)
            throws InterruptedException {
        return publisher.awaitServerAccepted(messageId, timeoutMs);
    }
}
