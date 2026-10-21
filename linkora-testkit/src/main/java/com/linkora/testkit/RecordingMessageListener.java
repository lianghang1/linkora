package com.linkora.testkit;

import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.MessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 录制型消息监听器（testkit）：收集收到的消息供测试断言，可选自动 ackProcessed。
 *
 * <p>用法：</p>
 * <pre>
 *   RecordingMessageListener listener = new RecordingMessageListener();
 *   client.addListener("greeting", listener);
 *   ...
 *   listener.awaitCount(1, 5000);
 *   assertEquals("hello", listener.messages().get(0).getPayloadAsUtf8String());
 * </pre>
 */
public final class RecordingMessageListener implements MessageListener {

    private final boolean autoAckProcessed;
    private final CopyOnWriteArrayList<LinkoraReceivedMessage> received =
            new CopyOnWriteArrayList<LinkoraReceivedMessage>();

    /** 自动 ackProcessed（多数测试场景）。 */
    public RecordingMessageListener() {
        this(true);
    }

    /** autoAckProcessed=false 时由测试自行控制 ACK 语义。 */
    public RecordingMessageListener(boolean autoAckProcessed) {
        this.autoAckProcessed = autoAckProcessed;
    }

    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        received.add(message);
        if (autoAckProcessed) {
            message.ackProcessed();
        }
    }

    /** 已收到的消息快照（按到达顺序）。 */
    public List<LinkoraReceivedMessage> messages() {
        return new ArrayList<LinkoraReceivedMessage>(received);
    }

    public int count() {
        return received.size();
    }

    /** 轮询等待消息数达到期望值；超时返回 false。 */
    public boolean awaitCount(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (received.size() >= expected) {
                return true;
            }
            Thread.sleep(20);
        }
        return received.size() >= expected;
    }

    public void clear() {
        received.clear();
    }
}
