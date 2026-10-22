package com.linkora.example.mvcclient.service;

import com.linkora.client.LinkoraClient;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.ClientStore;

import java.nio.charset.StandardCharsets;

/**
 * 上行服务公共基类：封装 publish（写 SQLite outbox，进程崩溃不丢，后台线程
 * 自动 POST 服务端）与 {@link #awaitServerAccepted}（轮询本地队列确认服务端已接受）。
 *
 * @author lianghang
 */
abstract class AbstractUplinkService {

    protected final LinkoraClient client;
    protected final ClientStore store;

    protected AbstractUplinkService(LinkoraClient client, ClientStore store) {
        this.client = client;
        this.store = store;
    }

    /** 发送一条文本上行，返回消息 ID（此时可能尚未到达服务端）。 */
    protected String publishText(String topic, String text) {
        return client.publish(topic, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 轮询本地队列，等待该消息被服务端确认（outbox 状态 SERVER_ACCEPTED）。 */
    public boolean awaitServerAccepted(String messageId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (store.findOutbox(messageId)
                    .filter(entry -> entry.getStatus() == OutboxStatus.SERVER_ACCEPTED)
                    .isPresent()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
