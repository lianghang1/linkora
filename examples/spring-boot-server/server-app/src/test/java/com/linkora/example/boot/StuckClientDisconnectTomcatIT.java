package com.linkora.example.boot;

import com.linkora.core.message.MessageState;
import com.linkora.server.LinkoraServer;
import com.linkora.server.MessageDeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验收基线 IT（Tomcat 容器；SSE 写模型修复后本测试必须秒级自愈）：验证"假在线"客户端（订阅后不读数据）塞满服务端缓冲区导致
 * 编排线程写阻塞后的行为——踢连接（disconnectClient）能否中断阻塞写、不干预时容器自愈耗时。
 *
 * 判定信号：阻塞写失败后消息离开 ACCEPTED 进入 RETRYING（编排线程恢复的铁证，
 * 比 SSE 握手可靠——初始帧由 Servlet 线程写，不走编排线程）。
 * 与 testkit 的 Jetty 版（StuckClientDisconnectSpikeTest）同一场景——两个容器独立实现，行为不能假设一致。
 *
 * @author lianghang
 */
@SpringBootTest(classes = SpringBootServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "linkora.server.register-clients.stuck-store=true",
                "linkora.server.register-clients.stuck-ctrl=true"
        })
class StuckClientDisconnectTomcatIT {

    @LocalServerPort
    private int port;

    @Autowired
    private LinkoraServer linkoraServer;

    @Test
    void kickVersusNoKickTiming() throws Exception {
        // ===== 场景 A：卡住后踢连接 =====
        Socket stuck = openStuckAndFill("stuck-store");
        String msgId = lastPublishedMessageId("stuck-store");
        assertEquals(MessageState.ACCEPTED, currentState(msgId, 5000),
                "灌缓冲区后消息应停在 ACCEPTED（写阻塞中）");

        long kickStart = System.currentTimeMillis();
        int closed = linkoraServer.disconnectClient("stuck-store");
        long unblock = awaitLeaveAccepted(msgId, 90_000);
        System.out.println("[tomcat] 场景A（kick): disconnectClient=" + closed
                + "，写阻塞解除耗时 " + unblock + "ms（含 kick 调用本身）");
        stuck.close();
        Thread.sleep(1500); // 让队列里第二发大消息也失败出队

        // ===== 场景 B：卡住后不干预（对照组）=====
        Socket ctrl = openStuckAndFill("stuck-ctrl");
        String ctrlMsg = lastPublishedMessageId("stuck-ctrl");
        assertEquals(MessageState.ACCEPTED, currentState(ctrlMsg, 5000));
        long bStart = System.currentTimeMillis();
        long autoRecover = awaitLeaveAccepted(ctrlMsg, 120_000);
        System.out.println("[tomcat] 场景B（不干预）: 自愈耗时 " + autoRecover + "ms");
        ctrl.close();

        System.out.println("[tomcat] ===== 结论 =====");
        System.out.println("[tomcat] kick 解除: " + unblock + "ms | 不干预自愈: " + autoRecover + "ms");
        assertTrue(autoRecover > 0 && autoRecover < 120_000, "不干预场景也必须自愈（容器异步超时应兜底）");
    }

    /** 裸 socket 订阅后停止读取，并灌两条 1MB 大消息塞满缓冲区。 */
    private Socket openStuckAndFill(String clientId) throws Exception {
        Socket s = new Socket("127.0.0.1", port);
        s.setReceiveBufferSize(8 * 1024);
        OutputStream out = s.getOutputStream();
        out.write(("GET /events?clientId=" + clientId + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = s.getInputStream();
        byte[] head = new byte[2048];
        int n = in.read(head);
        String headStr = new String(head, 0, Math.max(0, n), StandardCharsets.UTF_8);
        assertFalse(headStr.contains("404"), "订阅应成功: " + headStr.lines().findFirst().orElse(""));

        byte[] big = new byte[1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'A');
        linkoraServer.publish(clientId, "greeting", big);
        linkoraServer.publish(clientId, "greeting", big);
        Thread.sleep(2500); // 给推送线程时间真正阻塞在 flush 上
        return s;
    }

    private String lastPublishedMessageId(String clientId) {
        // publish 返回 PublishResult；为拿到 messageId 简化：直接查该客户端消息列表
        return linkoraServer.listMessages(clientId, null, null, null, 0, 1).get(0).getMessageId();
    }

    private MessageState currentState(String messageId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<MessageDeliveryStatus> status = linkoraServer.getMessageStatus(messageId);
            if (status.isPresent() && !status.get().getDeliveryRecords().isEmpty()) {
                return status.get().getDeliveryRecords().get(0).getState();
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** 等待消息离开 ACCEPTED（写失败进 RETRYING = 编排线程恢复）。返回耗时；超时返回 -1。 */
    private long awaitLeaveAccepted(String messageId, long timeoutMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        long deadline = start + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MessageState state = currentState(messageId, 200);
            if (state != null && state != MessageState.ACCEPTED) {
                return System.currentTimeMillis() - start;
            }
            Thread.sleep(200);
        }
        return -1;
    }
}
