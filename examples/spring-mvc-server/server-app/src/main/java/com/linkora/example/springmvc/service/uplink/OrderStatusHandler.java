package com.linkora.example.springmvc.service.uplink;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.serialize.jackson.JsonUplinkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单状态处理器（线上侧）：处理线下门店回传的订单状态变更，继承 SDK 的
 * {@link JsonUplinkHandler}，payload 自动反序列化为 {@link StatusPayload}。
 * payload：{@code {"orderNo":"SO-1001","status":"SHIPPED"}}。
 *
 * @author lianghang
 */
@Service
public class OrderStatusHandler extends JsonUplinkHandler<OrderStatusHandler.StatusPayload> {

    private static final Logger log = LoggerFactory.getLogger(OrderStatusHandler.class);

    /** 上行 topic：线下门店更改订单状态。 */
    public static final String TOPIC_ORDER_STATUS_UPDATED = "order-status-updated";

    /** 已收到的状态变更（演示用内存记录，真实场景应落库）。 */
    private final List<OrderStatusUpdate> received = new CopyOnWriteArrayList<OrderStatusUpdate>();

    public OrderStatusHandler() {
        super(StatusPayload.class);
    }

    @Override
    public String messageType() {
        return TOPIC_ORDER_STATUS_UPDATED;
    }

    @Override
    protected void handle(LinkoraMessage message, StatusPayload payload) {
        received.add(new OrderStatusUpdate(payload.orderNo, payload.status, message.getClientId(),
                System.currentTimeMillis()));
        log.info("线下订单状态变更: clientId={}, orderNo={}, status={}, messageId={}",
                message.getClientId(), payload.orderNo, payload.status, message.getMessageId());
        System.out.println("[上行-订单状态] clientId=" + message.getClientId()
                + " 订单 " + payload.orderNo + " → " + payload.status);
    }

    /** 订单状态 payload DTO。 */
    public static final class StatusPayload {
        public String orderNo;
        public String status;
    }

    /** 已处理的状态变更列表（供 GET /api/uplink/order-status-updates 查询）。 */
    public List<OrderStatusUpdate> getReceived() {
        return new ArrayList<OrderStatusUpdate>(received);
    }

    /** 一条订单状态变更记录（演示用简单值对象）。 */
    public static final class OrderStatusUpdate {
        private final String orderNo;
        private final String status;
        private final String clientId;
        private final long receivedAt;

        public OrderStatusUpdate(String orderNo, String status, String clientId, long receivedAt) {
            this.orderNo = orderNo;
            this.status = status;
            this.clientId = clientId;
            this.receivedAt = receivedAt;
        }

        public String getOrderNo() {
            return orderNo;
        }

        public String getStatus() {
            return status;
        }

        public String getClientId() {
            return clientId;
        }

        public long getReceivedAt() {
            return receivedAt;
        }
    }
}
