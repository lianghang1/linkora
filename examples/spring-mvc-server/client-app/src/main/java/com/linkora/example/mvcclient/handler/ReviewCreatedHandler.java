package com.linkora.example.mvcclient.handler;

import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.MessageListener;

import java.util.concurrent.CountDownLatch;

/**
 * 评价通知处理器（线下门店侧）：收到线上的 review-created 通知后
 * 解析、打印、模拟门店查看评价，最后显式 ackProcessed。
 * 消息格式：{@code 评价ID|评价内容}。
 * CountDownLatch 仅用于演示主流程等待首条通知，传 null 则只处理不等待。
 *
 * @author lianghang
 */
public class ReviewCreatedHandler implements MessageListener {

    /** 下行 topic：线上新评价通知（与线上 ReviewService 发布的类型一致）。 */
    public static final String TOPIC = "review-created";

    private final CountDownLatch firstReview;

    public ReviewCreatedHandler(CountDownLatch firstReview) {
        this.firstReview = firstReview;
    }

    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        String[] parts = message.getPayloadAsUtf8String().split("\\|", 2);
        System.out.println("[下行-新评价] 评价ID=" + parts[0]
                + " 内容=" + (parts.length > 1 ? parts[1] : "")
                + " id=" + message.getMessageId());
        // 业务处理成功后显式 ACK，服务端才把该条投递标记为完成
        message.ackProcessed();
        if (firstReview != null) {
            firstReview.countDown();
        }
    }
}
