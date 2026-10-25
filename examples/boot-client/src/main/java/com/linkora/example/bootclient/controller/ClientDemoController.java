package com.linkora.example.bootclient.controller;

import com.linkora.example.bootclient.service.OrderStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端演示 Controller：同库同事务演示入口（datasource 模式）。
 *
 * @author lianghang
 */
@RestController
public class ClientDemoController {

    private final OrderStatusService orderStatusService;

    public ClientDemoController(OrderStatusService orderStatusService) {
        this.orderStatusService = orderStatusService;
    }

    /** 门店发货：更新状态并通知线上（普通路径）。 */
    @PostMapping("/api/ship")
    public Map<String, Object> ship(@RequestParam String orderNo,
                                    @RequestParam(defaultValue = "SHIPPED") String status) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        String messageId = orderStatusService.updateStatus(orderNo, status);
        body.put("messageId", messageId);
        return body;
    }

    /**
     * 同库同事务演示（datasource 模式）：fail=true 时 publish 之后整体回滚——
     * 业务行与 outbox 消息一起消失；随后 GET /api/demo/shipment-exists 查证。
     */
    @PostMapping("/api/demo/atomic-ship")
    public Map<String, Object> atomicShip(@RequestParam String orderNo,
                                          @RequestParam(defaultValue = "SHIPPED") String status,
                                          @RequestParam(defaultValue = "false") boolean fail) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (!orderStatusService.isTxAvailable()) {
            body.put("note", "同事务演示仅 datasource 模式可用"
                    + "（启动加 --spring.profiles.active=datasource；sqlite 模式下本地队列与业务库不在同一事务域）");
            return body;
        }
        try {
            String messageId = orderStatusService.updateStatusAtomically(orderNo, status, fail);
            body.put("messageId", messageId);
            body.put("committed", true);
        } catch (IllegalStateException e) {
            body.put("committed", false);
            body.put("note", "业务已回滚：业务行与消息一起消失");
        }
        return body;
    }

    /** 查证回滚效果：发货记录是否真实落库。 */
    @GetMapping("/api/demo/shipment-exists")
    public Map<String, Object> shipmentExists(@RequestParam String orderNo) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (!orderStatusService.isTxAvailable()) {
            body.put("note", "仅 datasource 模式提供业务表（见 /api/demo/atomic-ship）");
            return body;
        }
        body.put("orderNo", orderNo);
        body.put("exists", orderStatusService.shipmentExists(orderNo));
        return body;
    }
}
