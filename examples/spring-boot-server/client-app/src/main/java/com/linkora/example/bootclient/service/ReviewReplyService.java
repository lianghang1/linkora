package com.linkora.example.bootclient.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.client.UplinkPublisher;
import com.linkora.serialize.jackson.LinkoraJsonUtil;
import org.springframework.stereotype.Service;

/**
 * 评价回复服务（线下门店侧）：门店回复用户评价后通知线上。
 * payload 为 JSON：{@code {"reviewId":"RV-2001","reply":"感谢反馈"}}，
 * 线上由 ReviewReplyHandler 处理；发布/确认能力由 SDK 的
 * {@link UplinkPublisher}（客户端装配自动注册的 bean）提供。
 *
 * @author lianghang
 */
@Service
public class ReviewReplyService {

    /** 上行 topic：与线上 ReviewReplyHandler.messageType() 一致。 */
    public static final String TOPIC = "review-reply";

    private final UplinkPublisher publisher;

    public ReviewReplyService(UplinkPublisher publisher) {
        this.publisher = publisher;
    }

    /** 线下回复评价并通知线上，返回消息 ID（用 awaitServerAccepted 等确认）。 */
    public String reply(String reviewId, String replyText) {
        ObjectNode payload = LinkoraJsonUtil.getObjectMapper().createObjectNode();
        payload.put("reviewId", reviewId);
        payload.put("reply", replyText);
        return publisher.publish(TOPIC, payload);
    }

    /** 等待该消息被服务端确认（转发 {@link UplinkPublisher#awaitServerAccepted}）。 */
    public boolean awaitServerAccepted(String messageId, long timeoutMs)
            throws InterruptedException {
        return publisher.awaitServerAccepted(messageId, timeoutMs);
    }
}
