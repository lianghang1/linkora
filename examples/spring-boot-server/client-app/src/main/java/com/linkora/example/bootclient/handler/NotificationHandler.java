package com.linkora.example.bootclient.handler;

import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.MessageListener;

import java.util.concurrent.CountDownLatch;

/**
 * 下行通知处理器：实现 {@link MessageListener}，收到服务端消息后打印并显式
 * ackProcessed（业务失败场景改调 message.ackFailed(...)，服务端按策略重试）。
 * CountDownLatch 仅用于演示主流程等待首条消息，传 null 则只处理不等待。
 *
 * @author lianghang
 */
public class NotificationHandler implements MessageListener {

    private final CountDownLatch firstNotification;

    public NotificationHandler(CountDownLatch firstNotification) {
        this.firstNotification = firstNotification;
    }

    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        System.out.println("[下行] topic=" + message.getMessageType()
                + " payload=" + message.getPayloadAsUtf8String()
                + " id=" + message.getMessageId());
        // 业务处理成功后显式 ACK，服务端才把该条投递标记为完成
        message.ackProcessed();
        if (firstNotification != null) {
            firstNotification.countDown();
        }
    }
}
