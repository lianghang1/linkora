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

    public OrderService(LinkoraServer linkoraServer) {
        this.linkoraServer = linkoraServer;
    }

    /**
     * 线上下单并通知线下门店：先持久化（门店离线则上线补投），在线时经 SSE 实时送达。
     */
    public PublishResult placeOrder(String clientId, String orderNo, String item) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("orderNo", orderNo);
        payload.put("item", item);
        return linkoraServer.publish(clientId, TOPIC_ORDER_CREATED,
                payload.toString().getBytes(StandardCharsets.UTF_8));
    }
}
