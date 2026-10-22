package com.linkora.example.mvcclient.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.client.LinkoraClient;
import com.linkora.core.storage.client.ClientStore;
import org.springframework.stereotype.Service;

/**
 * 评价回复服务（线下门店侧）：门店回复用户评价后通知线上。
 * payload 为 JSON：{@code {"reviewId":"RV-2001","reply":"感谢反馈"}}，
 * 线上由 ReviewReplyHandler 处理。
 *
 * @author lianghang
 */
@Service
public class ReviewReplyService extends AbstractUplinkService {

    /** 上行 topic：评价回复（与线上 ReviewReplyHandler 的类型一致）。 */
    public static final String TOPIC_REVIEW_REPLY = "review-reply";

    public ReviewReplyService(LinkoraClient client, ClientStore store) {
        super(client, store);
    }

    /** 线下回复评价并通知线上，返回消息 ID（用 awaitServerAccepted 等确认）。 */
    public String reply(String reviewId, String replyText) {
        ObjectNode payload = newPayload();
        payload.put("reviewId", reviewId);
        payload.put("reply", replyText);
        return publish(TOPIC_REVIEW_REPLY, payload);
    }
}
