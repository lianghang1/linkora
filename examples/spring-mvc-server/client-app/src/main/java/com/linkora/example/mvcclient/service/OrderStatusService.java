package com.linkora.example.mvcclient.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.client.UplinkPublisher;
import com.linkora.serialize.jackson.LinkoraJsonUtil;
import org.springframework.stereotype.Service;

/**
 * 订单状态服务（线下门店侧）：门店更改订单状态后调用线上。
 * payload 为 JSON：{@code {"orderNo":"SO-1001","status":"SHIPPED"}}，
 * 线上由 OrderStatusHandler 处理；发布/确认能力由 SDK 的
 * {@link UplinkPublisher}（客户端装配自动注册的 bean）提供。
 *
 * @author lianghang
 */
@Service
public class OrderStatusService {

    /** 上行 topic：与线上 OrderStatusHandler.messageType() 一致。 */
    public static final String TOPIC = "order-status-updated";

    private final UplinkPublisher publisher;

    public OrderStatusService(UplinkPublisher publisher) {
        this.publisher = publisher;
    }

    /** 更新订单状态并通知线上，返回消息 ID。 */
    public String updateStatus(String orderNo, String status) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("status", status);
        // sequenceKey = orderNo：同一订单的状态变更严格按发布顺序被线上接收
        return publisher.publish(TOPIC, payload, orderNo);
    }

    /** 等待该消息被服务端确认（转发 {@link UplinkPublisher#awaitServerAccepted}）。 */
    public boolean awaitServerAccepted(String messageId, long timeoutMs)
            throws InterruptedException {
        return publisher.awaitServerAccepted(messageId, timeoutMs);
    }
}
