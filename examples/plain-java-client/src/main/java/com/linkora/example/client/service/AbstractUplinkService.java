package com.linkora.example.client.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.client.LinkoraClient;
import com.linkora.client.OutboxListener;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.serialize.jackson.LinkoraJsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 上行服务公共基类：封装 publish（写 SQLite outbox，进程崩溃不丢，后台线程
 * 自动 POST 服务端）与 {@link #awaitServerAccepted}（基于框架
 * {@link OutboxListener} 回调等待服务端确认，替代对本地队列的轮询）。
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

    /** 发布一条 JSON 上行消息，返回消息 ID（此时可能尚未到达服务端）。 */
    protected String publish(String topic, ObjectNode payload) {
        return client.publish(topic, payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 新建 JSON payload 对象。 */
    protected static ObjectNode newPayload() {
        return LinkoraJsonUtil.getObjectMapper().createObjectNode();
    }

    /**
     * 等待该消息被服务端确认（outbox → SERVER_ACCEPTED）：
     * 注册 {@link OutboxListener} 回调等待，避免轮询；已确认则立即返回。
     */
    public boolean awaitServerAccepted(final String messageId, long timeoutMs)
            throws InterruptedException {
        final CountDownLatch accepted = new CountDownLatch(1);
        OutboxListener listener = new OutboxListener() {
            @Override
            public void onStatusChanged(OutboxEntry entry, OutboxStatus previous) {
                if (entry.getStatus() == OutboxStatus.SERVER_ACCEPTED
                        && entry.getMessageId().equals(messageId)) {
                    accepted.countDown();
                }
            }
        };
        // 先挂监听再查存量状态，避免"已确认但错过回调"的竞态
        client.addOutboxListener(listener);
        try {
            if (store.findOutbox(messageId)
                    .filter(entry -> entry.getStatus() == OutboxStatus.SERVER_ACCEPTED)
                    .isPresent()) {
                return true;
            }
            return accepted.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            client.removeOutboxListener(listener);
        }
    }
}
