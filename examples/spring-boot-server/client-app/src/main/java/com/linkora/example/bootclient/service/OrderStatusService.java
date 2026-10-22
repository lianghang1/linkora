package com.linkora.example.bootclient.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.client.LinkoraClient;
import com.linkora.core.storage.client.ClientStore;
import org.springframework.stereotype.Service;

/**
 * 订单状态服务（线下门店侧）：门店更改订单状态后调用线上。
 * payload 为 JSON：{@code {"orderNo":"SO-1001","status":"SHIPPED"}}，
 * 线上由 OrderStatusHandler 处理。
 *
 * @author lianghang
 */
@Service
public class OrderStatusService extends AbstractUplinkService {

    /** 上行 topic：订单状态变更（与线上 OrderStatusHandler 的类型一致）。 */
    public static final String TOPIC_ORDER_STATUS_UPDATED = "order-status-updated";

    public OrderStatusService(LinkoraClient client, ClientStore store) {
        super(client, store);
    }

    /** 线下更改订单状态并通知线上，返回消息 ID（用 awaitServerAccepted 等确认）。 */
    public String updateStatus(String orderNo, String newStatus) {
        ObjectNode payload = newPayload();
        payload.put("orderNo", orderNo);
        payload.put("status", newStatus);
        return publish(TOPIC_ORDER_STATUS_UPDATED, payload);
    }
}
