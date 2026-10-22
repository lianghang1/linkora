package com.linkora.example.boot.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.example.boot.support.JsonUtil;
import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 评价服务（线上侧）：用户评价后经 {@link LinkoraServer} 通知线下门店。
 * payload 为 JSON：{@code {"reviewId":"RV-2001","content":"包装完好，五星好评"}}。
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
        ObjectNode payload = JsonUtil.MAPPER.createObjectNode();
        payload.put("reviewId", reviewId);
        payload.put("content", content);
        return linkoraServer.publish(clientId, TOPIC_REVIEW_CREATED,
                payload.toString().getBytes(StandardCharsets.UTF_8));
    }
}
