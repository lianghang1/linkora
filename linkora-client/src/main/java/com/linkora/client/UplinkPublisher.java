package com.linkora.client;

import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.core.message.OutboxStatus;
import com.linkora.client.OutboxListener;
import com.linkora.serialize.jackson.LinkoraJsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 上行发送便捷入口：封装"发布（写本地队列，进程崩溃不丢，后台线程自动 POST
 * 服务端）+ 等待服务端接受确认"两步操作，宿主业务代码直接注入使用。
 *
 * <p>Spring 宿主：客户端装配自动注册本类 bean（Boot 宿主由 starter 提供，
 * MVC 宿主由 LinkoraClientConfiguration 提供）；非 Spring 宿主手动构造。</p>
 *
 * @author lianghang
 */
public class UplinkPublisher {

    private final LinkoraClient client;
    private final ClientStore store;

    public UplinkPublisher(LinkoraClient client, ClientStore store) {
        this.client = client;
        this.store = store;
    }

    /**
     * 发布一条 JSON 上行消息（对象经内置 {@link LinkoraJsonUtil} 序列化），
     * 返回消息 ID（此时可能尚未到达服务端）。
     */
    public String publish(String messageType, Object payload) {
        return publish(messageType, payload, null);
    }

    /** 发布带顺序键的上行消息（同 key 严格按调用顺序发送，前一条未终结前不发后一条）。 */
    public String publish(String messageType, Object payload, String sequenceKey) {
        return client.publish(messageType,
                LinkoraJsonUtil.toJson(payload).getBytes(StandardCharsets.UTF_8), sequenceKey);
    }

    /** 发布一条原始字节上行消息，返回消息 ID（此时可能尚未到达服务端）。 */
    public String publish(String messageType, byte[] payload) {
        return client.publish(messageType, payload);
    }

    /** 发布带顺序键的字节上行消息。 */
    public String publish(String messageType, byte[] payload, String sequenceKey) {
        return client.publish(messageType, payload, sequenceKey);
    }

    /**
     * 等待指定消息被服务端确认（outbox 状态到达 SERVER_ACCEPTED）：
     * 基于 {@link OutboxListener} 回调等待，避免轮询；已确认则立即返回。
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
