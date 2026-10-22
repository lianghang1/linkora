package com.linkora.example.server.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linkora.example.server.service.OrderService;
import com.linkora.example.server.service.ReviewService;
import com.linkora.example.server.service.uplink.OrderStatusHandler;
import com.linkora.example.server.service.uplink.ReviewReplyHandler;
import com.linkora.example.server.support.JsonUtil;
import com.linkora.server.PublishResult;

import java.io.IOException;
import java.util.List;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 线上平台业务 Controller（普通 Servlet 实现）：只做参数接收、调用 service、
 * 组装 JSON 响应。业务逻辑全部在 service 层——下行发布在
 * {@link OrderService}/{@link ReviewService}，上行处理在 service.uplink 包的两个 handler。
 *
 * <p>路由（挂载在 {@code /api/*}）：</p>
 * <ul>
 *   <li>POST /api/orders —— 线上下单，通知线下门店；</li>
 *   <li>POST /api/reviews —— 线上评价，通知线下门店；</li>
 *   <li>GET /api/uplink/order-status-updates —— 查看线下回传的订单状态；</li>
 *   <li>GET /api/uplink/review-replies —— 查看线下回复的评价。</li>
 * </ul>
 *
 * @author lianghang
 */
public class PlatformController extends HttpServlet {

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

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if ("/orders".equals(path)) {
            publishResult(resp, orderService.placeOrder(
                    req.getParameter("clientId"),
                    req.getParameter("orderNo"),
                    req.getParameter("item")));
        } else if ("/reviews".equals(path)) {
            publishResult(resp, reviewService.createReview(
                    req.getParameter("clientId"),
                    req.getParameter("reviewId"),
                    req.getParameter("content")));
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "未知接口: " + path);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getPathInfo();
        if ("/uplink/order-status-updates".equals(path)) {
            writeJson(resp, orderStatusHandler.getReceived());
        } else if ("/uplink/review-replies".equals(path)) {
            writeJson(resp, reviewReplyHandler.getReceived());
        } else {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "未知接口: " + path);
        }
    }

    private void publishResult(HttpServletResponse resp, PublishResult result) throws IOException {
        ObjectNode body = JsonUtil.MAPPER.createObjectNode();
        body.put("messageId", result.getMessageId());
        body.put("duplicate", result.isDuplicate());
        writeJson(resp, body);
    }

    private void writeJson(HttpServletResponse resp, Object body) throws IOException {
        byte[] bytes = JsonUtil.MAPPER.writeValueAsBytes(body);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setContentLength(bytes.length);
        resp.getOutputStream().write(bytes);
    }
}
