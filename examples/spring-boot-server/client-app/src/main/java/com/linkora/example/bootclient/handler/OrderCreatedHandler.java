package com.linkora.example.bootclient.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.MessageListener;
import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.serialize.jackson.LinkoraJsonUtil;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 新订单处理器（线下门店侧）：收到线上的 order-created 通知后解析 JSON、
 * 打印、模拟门店接单，最后显式 ackProcessed。
 * payload：{@code {"orderNo":"SO-2002","item":"机械键盘 x1"}}。
 * CountDownLatch 仅用于演示主流程等待首条通知，传 null 则只处理不等待。
 *
 * <p>顺序键演示：orderNo=SO-9003 的第一条消息模拟"门店系统故障"回 ACK FAILED
 * （服务端按策略重试），用于真实链路验证"同键第二条在前一条终结前不会被推送"。</p>
 *
 * @author lianghang
 */
public class OrderCreatedHandler implements MessageListener {

    /** 下行 topic：线上新订单通知（与线上 OrderService 发布的类型一致）。 */
    public static final String TOPIC = "order-created";

    private final CountDownLatch firstOrder;

    /** 顺序键演示：已失败过一次的 messageId（第二次到达时改为成功）。 */
    private final Set<String> failedOnce = ConcurrentHashMap.newKeySet();

    public OrderCreatedHandler(CountDownLatch firstOrder) {
        this.firstOrder = firstOrder;
    }

    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        JsonNode payload;
        try {
            payload = LinkoraJsonUtil.readTree(message.getPayload());
        } catch (com.linkora.core.error.LinkoraException e) {
            // 业务处理失败显式 ACK FAILED，服务端按策略重试
            message.ackFailed(LinkoraErrorCode.INVALID_MESSAGE,
                    "payload 不是合法 JSON: " + e.getMessage());
            return;
        }
        String orderNo = payload.path("orderNo").asText("");
        // 顺序键演示分支：仅 SO-9003 的第一条（item=A）首次到达时模拟处理失败
        if ("SO-9003".equals(orderNo) && "A".equals(payload.path("item").asText())
                && failedOnce.add(message.getMessageId())) {
            System.out.println("[顺序键演示] SO-9003 首次处理失败（模拟门店系统故障）"
                    + " → ack FAILED，服务端将重试；同键后续消息此时必须被门禁挡住");
            message.ackFailed(LinkoraErrorCode.INTERNAL_ERROR, "模拟门店系统故障");
            return;
        }
        System.out.println("[下行-新订单] 订单号=" + orderNo
                + " 商品=" + payload.path("item").asText("")
                + " 顺序键=" + message.getMessage().getSequenceKey()
                + " id=" + message.getMessageId());
        // 业务处理成功后显式 ACK，服务端才把该条投递标记为完成
        // （失败场景改调 message.ackFailed(...)，服务端按策略重试）
        message.ackProcessed();
        if (firstOrder != null) {
            firstOrder.countDown();
        }
    }
}
