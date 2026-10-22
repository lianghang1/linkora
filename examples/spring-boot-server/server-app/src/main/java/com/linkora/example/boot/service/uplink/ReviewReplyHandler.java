package com.linkora.example.boot.service.uplink;

import com.linkora.core.message.LinkoraMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 评价回复处理器（线上侧）：处理线下门店对用户评价的回复。
 * 消息格式（UTF-8 文本）：{@code 评价ID|回复内容}，如 {@code RV-2001|感谢反馈}。
 *
 * @author lianghang
 */
@Service
public class ReviewReplyHandler implements UplinkHandler {

    private static final Logger log = LoggerFactory.getLogger(ReviewReplyHandler.class);

    /** 上行 topic：线下门店回复评价。 */
    public static final String TOPIC_REVIEW_REPLY = "review-reply";

    /** 已收到的回复（演示用内存记录，真实场景应落库）。 */
    private final List<ReviewReply> received = new CopyOnWriteArrayList<ReviewReply>();

    @Override
    public String messageType() {
        return TOPIC_REVIEW_REPLY;
    }

    @Override
    public void handle(LinkoraMessage message) {
        String[] parts = message.getPayloadAsUtf8String().split("\\|", 2);
        String reviewId = parts[0];
        String reply = parts.length > 1 ? parts[1] : "";
        received.add(new ReviewReply(reviewId, reply, message.getClientId(),
                System.currentTimeMillis()));
        log.info("线下评价回复: clientId={}, reviewId={}, reply={}, messageId={}",
                message.getClientId(), reviewId, reply, message.getMessageId());
        System.out.println("[上行-评价回复] clientId=" + message.getClientId()
                + " 评价 " + reviewId + " ← " + reply);
    }

    /** 已处理的回复列表（供 GET /api/uplink/review-replies 查询）。 */
    public List<ReviewReply> getReceived() {
        return new ArrayList<ReviewReply>(received);
    }

    /** 一条评价回复记录（演示用简单值对象）。 */
    public static final class ReviewReply {
        private final String reviewId;
        private final String reply;
        private final String clientId;
        private final long receivedAt;

        public ReviewReply(String reviewId, String reply, String clientId, long receivedAt) {
            this.reviewId = reviewId;
            this.reply = reply;
            this.clientId = clientId;
            this.receivedAt = receivedAt;
        }

        public String getReviewId() {
            return reviewId;
        }

        public String getReply() {
            return reply;
        }

        public String getClientId() {
            return clientId;
        }

        public long getReceivedAt() {
            return receivedAt;
        }
    }
}
