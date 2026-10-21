package com.linkora.example.springmvc.service;

import com.linkora.server.LinkoraServer;
import com.linkora.server.PublishResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 下行通知服务：组装业务消息并经 {@link LinkoraServer} 发布给目标客户端。
 * Controller 只做参数接收与响应组装，消息发布细节集中在这一层。
 *
 * @author lianghang
 */
@Service
public class NotifyService {

    /** 下行通知 topic（客户端按该类型注册监听）。 */
    public static final String TOPIC_NOTIFICATION = "notification";

    /** 启动欢迎消息 topic（演示客户端离线发布、上线补投）。 */
    public static final String TOPIC_GREETING = "greeting";

    private final LinkoraServer linkoraServer;

    public NotifyService(LinkoraServer linkoraServer) {
        this.linkoraServer = linkoraServer;
    }

    /** Linkora 服务端运行状态。 */
    public boolean isRunning() {
        return linkoraServer.isRunning();
    }

    /**
     * 下发一条文本通知：先持久化（客户端离线则等上线补投），在线时经 SSE 实时推送。
     */
    public PublishResult sendNotification(String clientId, String text) {
        return linkoraServer.publish(clientId, TOPIC_NOTIFICATION,
                text.getBytes(StandardCharsets.UTF_8));
    }

    /** 下发欢迎消息（演示离线补投：服务端启动时客户端通常尚未连接）。 */
    public PublishResult sendGreeting(String clientId, String text) {
        return linkoraServer.publish(clientId, TOPIC_GREETING,
                text.getBytes(StandardCharsets.UTF_8));
    }
}
