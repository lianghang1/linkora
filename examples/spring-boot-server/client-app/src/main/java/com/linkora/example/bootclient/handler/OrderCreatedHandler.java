package com.linkora.example.bootclient.handler;

import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.MessageListener;

import java.util.concurrent.CountDownLatch;

/**
 * 新订单处理器（线下门店侧）：收到线上的 order-created 通知后
 * 解析、打印、模拟门店接单，最后显式 ackProcessed。
 * 消息格式：{@code 订单号|商品描述}。
 * CountDownLatch 仅用于演示主流程等待首条通知，传 null 则只处理不等待。
 *
 * @author lianghang
 */
public class OrderCreatedHandler implements MessageListener {

    /** 下行 topic：线上新订单通知（与线上 OrderService 发布的类型一致）。 */
    public static final String TOPIC = "order-created";

    private final CountDownLatch firstOrder;

    public OrderCreatedHandler(CountDownLatch firstOrder) {
        this.firstOrder = firstOrder;
    }

    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        String[] parts = message.getPayloadAsUtf8String().split("\\|", 2);
        System.out.println("[下行-新订单] 订单号=" + parts[0]
                + " 商品=" + (parts.length > 1 ? parts[1] : "")
                + " id=" + message.getMessageId());
        // 业务处理成功后显式 ACK，服务端才把该条投递标记为完成
        // （失败场景改调 message.ackFailed(...)，服务端按策略重试）
        message.ackProcessed();
        if (firstOrder != null) {
            firstOrder.countDown();
        }
    }
}
