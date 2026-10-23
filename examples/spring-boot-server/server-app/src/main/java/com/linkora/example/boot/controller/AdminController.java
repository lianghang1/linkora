package com.linkora.example.boot.controller;

import com.linkora.core.error.LinkoraException;
import com.linkora.server.LinkoraServer;
import com.linkora.server.admin.ClientOverviewView;
import com.linkora.server.admin.DeliveryView;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理接口演示（线上侧）：把 SDK 的人类可读管理视图直接透出为 HTTP 查询/处置端点。
 *
 * <p>⚠️ 仅演示用：生产环境必须给这些端点加鉴权与访问控制（SDK 只提供方法，
 * 不做鉴权），避免管理能力裸露。</p>
 *
 * @author lianghang
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final LinkoraServer linkoraServer;

    public AdminController(LinkoraServer linkoraServer) {
        this.linkoraServer = linkoraServer;
    }

    /** 全部客户端概览：谁在线、谁压着消息、谁有死信。 */
    @GetMapping("/clients")
    public List<ClientOverviewView> clients() {
        return linkoraServer.getClientOverviews();
    }

    /** 未终结投递明细（含重试中与死信）。 */
    @GetMapping("/unfinished")
    public List<DeliveryView> unfinished(@RequestParam String clientId,
                                         @RequestParam(defaultValue = "50") int limit) {
        return linkoraServer.listUnfinishedDeliveries(clientId, limit);
    }

    /** 死信列表（人工重放的对象）。 */
    @GetMapping("/dead-letters")
    public List<DeliveryView> deadLetters(@RequestParam String clientId,
                                          @RequestParam(defaultValue = "50") int limit) {
        return linkoraServer.listDeadLetters(clientId, limit);
    }

    /** 强制断开指定客户端的 SSE 连接（处置假在线；客户端自动重连补发）。 */
    @PostMapping("/disconnect")
    public Map<String, Object> disconnect(@RequestParam String clientId) {
        int closed = linkoraServer.disconnectClient(clientId);
        return result("closedConnections", closed);
    }

    /** 人工重放死信（DEAD_LETTER → RETRYING 并立即重推）。 */
    @PostMapping("/retry-dead-letter")
    public Map<String, Object> retryDeadLetter(@RequestParam String clientId,
                                               @RequestParam String messageId) {
        return result("replayed", linkoraServer.retryDeadLetter(clientId, messageId));
    }

    /** 人工跳过一条卡住的消息（→ EXPIRED 终态，同键后续立即放行）。 */
    @PostMapping("/skip")
    public Map<String, Object> skip(@RequestParam String clientId,
                                    @RequestParam String messageId) {
        return result("skipped", linkoraServer.skipMessage(clientId, messageId));
    }

    private static Map<String, Object> result(String key, Object value) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put(key, value);
        return body;
    }

    /** 参数错误转 400 提示（演示从简）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, Object> badRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", e.getMessage());
        return body;
    }

    /** 存储异常转 500 提示（演示从简）。 */
    @ExceptionHandler(LinkoraException.class)
    public Map<String, Object> storageError(LinkoraException e) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("error", e.getMessage());
        return body;
    }
}
