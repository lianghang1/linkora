package com.linkora.example.bootclient;

import com.linkora.client.LinkoraClient;
import com.linkora.example.bootclient.handler.NotificationHandler;
import com.linkora.example.bootclient.service.ReportService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Spring Boot 客户端示例入口：{@code linkora.client.*} 配置由 starter 自动装配
 * 出 {@link LinkoraClient}（start/close 全托管）。业务代码分两层——
 * handler/NotificationHandler 负责下行消息处理，service/ReportService 负责上行发送，
 * 本类只串演示流程。
 *
 * <p>演示闭环（对端为 server-app，默认 http://127.0.0.1:8082）：</p>
 * <ol>
 *   <li>注册下行监听器：收到服务端消息 → NotificationHandler 打印 → ackProcessed；</li>
 *   <li>上行发送：ReportService.publish 写入 SQLite outbox → 后台线程 POST →
 *       等待 SERVER_ACCEPTED；</li>
 *   <li>请求服务端业务接口 /api/notify 下发一条消息 → 监听器收到（SSE）→ ACK。</li>
 * </ol>
 *
 * <p>运行方式（仓库根目录，先启动 server-app；首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/spring-boot-server/client-app spring-boot:run
 * </pre>
 *
 * <p>演示完成后进程长驻（SSE 保持在线可继续收发），Ctrl+C 优雅关闭；
 * 在途消息状态已持久化，重启自动续跑。中断重启后未处理的历史消息会补投。</p>
 *
 * @author lianghang
 */
@SpringBootApplication
public class ClientDemoApplication {

    /** 下行通知 topic（server-app 的 NotifyService 以此类型发布）。 */
    static final String TOPIC_NOTIFICATION = "notification";

    private static final String SERVER_BASE = "http://127.0.0.1:8082";
    private static final String CLIENT_ID = "demo-client";

    public static void main(String[] args) {
        SpringApplication.run(ClientDemoApplication.class, args);
    }

    /**
     * 业务演示：依赖注入的 {@link LinkoraClient} 已由 starter 启动。
     *
     * @param client starter 自动装配的客户端
     * @param reportService 上行发送服务（示例业务层）
     */
    @Bean
    public CommandLineRunner linkoraDemo(LinkoraClient client, ReportService reportService) {
        return args -> {
            CountDownLatch notificationReceived = new CountDownLatch(1);

            // 1. 下行监听：收到消息 → NotificationHandler 打印 → 业务成功后 ackProcessed
            //    （失败场景用 message.ackFailed(LinkoraErrorCode.XXX, "原因")，服务端按策略重试）
            client.addListener(TOPIC_NOTIFICATION, new NotificationHandler(notificationReceived));
            System.out.println("[就绪] 监听器已注册（topic=" + TOPIC_NOTIFICATION + "），SSE 在线");

            // 2. 上行发送：ReportService 先落 SQLite outbox（进程崩溃不丢），后台线程自动 POST
            for (int i = 1; i <= 3; i++) {
                String body = "boot-client-report-" + i;
                String messageId = reportService.submit(body);
                boolean accepted = reportService.awaitServerAccepted(messageId, 30_000);
                System.out.println("[上行] " + body + " messageId=" + messageId
                        + " 服务端" + (accepted ? "已接受(SERVER_ACCEPTED)" : "接受超时(见日志)"));
            }

            // 3. 请求服务端业务接口下发一条消息（也可手动 curl 触发），经 SSE 推回本端
            int code = postNotify(SERVER_BASE, CLIENT_ID, "hello from boot client");
            System.out.println("[触发] POST /api/notify → HTTP " + code
                    + "，等待服务端下发经 SSE 推回…");
            boolean received = notificationReceived.await(30, TimeUnit.SECONDS);
            System.out.println(received
                    ? "[PASS] 闭环完成：上行被服务端接受，服务端下发的消息经 SSE 收到并已 ACK"
                    : "[FAIL] 30 秒内未收到服务端下发消息（确认 server-app 已启动且端口未被占用）");

            System.out.println("========================================");
            System.out.println("演示完成，客户端长驻运行（Ctrl+C 优雅关闭）");
            System.out.println("========================================");
        };
    }

    /** 调用 server-app 业务接口 POST /api/notify?clientId=..&text=.. 触发服务端下发。 */
    private static int postNotify(String serverBase, String clientId, String text) throws IOException {
        String query = "?clientId=" + URLEncoder.encode(clientId, "UTF-8")
                + "&text=" + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(serverBase + "/api/notify" + query)
                .openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(new byte[0]);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }
}
