package com.linkora.example.boot.controller;

import com.linkora.example.boot.service.ClientReportService;
import com.linkora.example.boot.service.NotifyService;
import com.linkora.server.PublishResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务 Controller：只做参数接收、调用 service、组装响应。
 * 下行发布在 {@link NotifyService}，上行处理在 {@link ClientReportService}。
 *
 * @author lianghang
 */
@RestController
public class NotifyController {

    private final NotifyService notifyService;
    private final ClientReportService clientReportService;

    public NotifyController(NotifyService notifyService,
                            ClientReportService clientReportService) {
        this.notifyService = notifyService;
        this.clientReportService = clientReportService;
    }

    /** 服务端运行状态。 */
    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverRunning", notifyService.isRunning());
        return body;
    }

    /** 下发通知：经 NotifyService 发布，SSE 在线实时送达、离线补投。 */
    @PostMapping("/api/notify")
    public Map<String, Object> notify(@RequestParam String clientId,
                                      @RequestParam String text) {
        PublishResult result = notifyService.sendNotification(clientId, text);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("messageId", result.getMessageId());
        body.put("duplicate", result.isDuplicate());
        return body;
    }

    /** 查看已收到的客户端上报（ClientReportService 处理结果）。 */
    @GetMapping("/api/reports")
    public List<ClientReportService.Report> reports() {
        return clientReportService.getReports();
    }
}
