package com.linkora.example.boot.service;

import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 订单服务（线上侧）：用户下单后经 {@link LinkoraServer} 通知线下门店。
 * 消息格式（UTF-8 文本）：{@code 订单号|商品描述}。
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
        return linkoraServer.publish(clientId, TOPIC_ORDER_CREATED,
                (orderNo + "|" + item).getBytes(StandardCharsets.UTF_8));
    }
}
