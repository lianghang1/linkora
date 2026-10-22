package com.linkora.example.boot.service;

import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 评价服务（线上侧）：用户评价后经 {@link LinkoraServer} 通知线下门店。
 * 消息格式（UTF-8 文本）：{@code 评价ID|评价内容}。
 *
 * @author lianghang
 */
@Service
public class ReviewService {

    /** 下行 topic：新评价通知（线下门店按此类型监听）。 */
    public static final String TOPIC_REVIEW_CREATED = "review-created";

    private final LinkoraServer linkoraServer;

    public ReviewService(LinkoraServer linkoraServer) {
        this.linkoraServer = linkoraServer;
    }

    /** 线上发布评价并通知线下门店。 */
    public PublishResult createReview(String clientId, String reviewId, String content) {
        return linkoraServer.publish(clientId, TOPIC_REVIEW_CREATED,
                (reviewId + "|" + content).getBytes(StandardCharsets.UTF_8));
    }
}
