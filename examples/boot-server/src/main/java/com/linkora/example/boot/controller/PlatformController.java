package com.linkora.example.boot.controller;

import com.linkora.example.boot.service.OrderService;
import com.linkora.example.boot.service.ReviewService;
import com.linkora.example.boot.service.uplink.OrderStatusHandler;
import com.linkora.example.boot.service.uplink.ReviewReplyHandler;
import com.linkora.server.PublishResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 线上平台 Controller：只做参数接收、调用 service、组装响应。
 * 下行发布在 OrderService/ReviewService，上行处理在 service.uplink 包的两个 handler。
 *
 * @author lianghang
 */
@RestController
public class PlatformController {

    private final OrderService orderService;
    private final ReviewService reviewService;
    private final OrderStatusHandler orderStatusHandler;
    private final ReviewReplyHandler reviewReplyHandler;

    public PlatformController(OrderService orderService,
                              ReviewService reviewService,
                              OrderStatusHandler orderStatusHandler,
                              ReviewReplyHandler reviewReplyHandler) {
        this.orderService = orderService;
        this.reviewService = reviewService;
        this.orderStatusHandler = orderStatusHandler;
        this.reviewReplyHandler = reviewReplyHandler;
    }

    /** 线上下单：经 OrderService 发布 order-created 通知线下门店。 */
    @PostMapping("/api/orders")
    public Map<String, Object> createOrder(@RequestParam String clientId,
                                           @RequestParam String orderNo,
                                           @RequestParam String item) {
        PublishResult result = orderService.placeOrder(clientId, orderNo, item);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("messageId", result.getMessageId());
        body.put("duplicate", result.isDuplicate());
        return body;
    }

    /** 无 sequenceKey 的下发（对照演示：不受任何顺序门禁影响）。 */
    @PostMapping("/api/notify-nokey")
    public Map<String, Object> placeOrderNoKey(@RequestParam String clientId,
                                               @RequestParam String orderNo,
                                               @RequestParam String item) {
        PublishResult result = orderService.placeOrderNoKey(clientId, orderNo, item);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("messageId", result.getMessageId());
        return body;
    }

    /**
     * 同库同事务演示（0.5.0）：业务行与消息一起提交 / 一起消失。
     * fail=true 时 publish 之后抛异常触发整体回滚，随后可用
     * GET /api/demo/order-exists?orderNo=... 查证"消息从未存在"。
     */
    @PostMapping("/api/demo/atomic-order")
    public Map<String, Object> placeOrderAtomically(@RequestParam String clientId,
                                                    @RequestParam String orderNo,
                                                    @RequestParam String item,
                                                    @RequestParam(defaultValue = "false") boolean fail) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        try {
            PublishResult result = orderService.placeOrderAtomically(clientId, orderNo, item, fail);
            body.put("messageId", result.getMessageId());
            body.put("committed", true);
        } catch (IllegalStateException e) {
            body.put("committed", false);
            body.put("note", "业务已回滚：业务行与消息一起消失");
        }
        return body;
    }

    /** 查证回滚效果：订单是否真实落库。 */
    @GetMapping("/api/demo/order-exists")
    public Map<String, Object> orderExists(@RequestParam String orderNo) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("orderNo", orderNo);
        body.put("exists", orderService.orderExists(orderNo));
        return body;
    }

    /** 线上评价：经 ReviewService 发布 review-created 通知线下门店。 */
    @PostMapping("/api/reviews")
    public Map<String, Object> createReview(@RequestParam String clientId,
                                            @RequestParam String reviewId,
                                            @RequestParam String content) {
        PublishResult result = reviewService.createReview(clientId, reviewId, content);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("messageId", result.getMessageId());
        body.put("duplicate", result.isDuplicate());
        return body;
    }

    /** 查看线下回传的订单状态（OrderStatusHandler 处理结果）。 */
    @GetMapping("/api/uplink/order-status-updates")
    public List<OrderStatusHandler.OrderStatusUpdate> orderStatusUpdates() {
        return orderStatusHandler.getReceived();
    }

    /** 查看线下回复的评价（ReviewReplyHandler 处理结果）。 */
    @GetMapping("/api/uplink/review-replies")
    public List<ReviewReplyHandler.ReviewReply> reviewReplies() {
        return reviewReplyHandler.getReceived();
    }
}
