package com.linkora.example.mvcclient.service;

import com.linkora.client.LinkoraClient;
import com.linkora.core.storage.client.ClientStore;
/**
 * 评价回复服务（线下门店侧）：门店回复用户评价后通知线上。
 * 消息格式：{@code 评价ID|回复内容}，线上由 ReviewReplyHandler 处理。
 *
 * @author lianghang
 */

public class ReviewReplyService extends AbstractUplinkService {

    /** 上行 topic：评价回复（与线上 ReviewReplyHandler 的类型一致）。 */
    public static final String TOPIC_REVIEW_REPLY = "review-reply";

    public ReviewReplyService(LinkoraClient client, ClientStore store) {
        super(client, store);
    }

    /** 线下回复评价并通知线上，返回消息 ID（用 awaitServerAccepted 等确认）。 */
    public String reply(String reviewId, String replyText) {
        return publishText(TOPIC_REVIEW_REPLY, reviewId + "|" + replyText);
    }
}
