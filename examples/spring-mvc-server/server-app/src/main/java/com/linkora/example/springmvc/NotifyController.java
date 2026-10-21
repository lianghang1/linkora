package com.linkora.example.springmvc;

import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务 Controller：直接注入 LinkoraServer bean 发布下行消息，
 * 展示"宿主业务代码与 Linkora 共处一个 Spring 容器"的集成形态。
 *
 * @author lianghang
 */
@RestController
public class NotifyController {

    private final LinkoraServer server;

    public NotifyController(LinkoraServer server) {
        this.server = server;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("serverRunning", server.isRunning());
        return body;
    }

    @PostMapping("/api/notify")
    public Map<String, Object> notify(@RequestParam String clientId,
                                      @RequestParam String text) {
        PublishResult result = server.publish(clientId, "notification",
                text.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("messageId", result.getMessageId());
        body.put("duplicate", result.isDuplicate());
        return body;
    }
}
