package com.linkora.example.mvcclient.service;

import com.linkora.client.LinkoraClient;
import com.linkora.core.storage.client.ClientStore;
/**
 * 订单状态服务（线下门店侧）：门店更改订单状态后调用线上。
 * 消息格式：{@code 订单号|新状态}，线上由 OrderStatusHandler 处理。
 *
 * @author lianghang
 */

public class OrderStatusService extends AbstractUplinkService {

    /** 上行 topic：订单状态变更（与线上 OrderStatusHandler 的类型一致）。 */
    public static final String TOPIC_ORDER_STATUS_UPDATED = "order-status-updated";

    public OrderStatusService(LinkoraClient client, ClientStore store) {
        super(client, store);
    }

    /** 线下更改订单状态并通知线上，返回消息 ID（用 awaitServerAccepted 等确认）。 */
    public String updateStatus(String orderNo, String newStatus) {
        return publishText(TOPIC_ORDER_STATUS_UPDATED, orderNo + "|" + newStatus);
    }
}
