package com.linkora.example.bootclient;

import com.linkora.client.LinkoraClient;
import com.linkora.example.bootclient.handler.OrderCreatedHandler;
import com.linkora.example.bootclient.handler.ReviewCreatedHandler;
import com.linkora.example.bootclient.service.OrderStatusService;
import com.linkora.example.bootclient.service.ReviewReplyService;
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
 * Spring Boot 客户端示例入口（线下门店侧）：{@code linkora.client.*} 配置由
 * starter 自动装配出 {@link LinkoraClient}（start/close 全托管）。
 * 业务代码分两层——handler 包两个下行处理器（新订单/新评价），
 * service 包两个上行服务（订单状态/评价回复），本类只串演示流程。
 *
 * <p>演示闭环（对端为 server-app，默认 http://127.0.0.1:8082）：</p>
 * <ol>
 *   <li>下行一：线上下单（POST /api/orders）→ OrderCreatedHandler 收到并 ACK；</li>
 *   <li>下行二：线上评价（POST /api/reviews）→ ReviewCreatedHandler 收到并 ACK；</li>
 *   <li>上行一：线下更改订单状态 → 服务端 OrderStatusHandler 处理；</li>
 *   <li>上行二：线下回复评价 → 服务端 ReviewReplyHandler 处理。</li>
 * </ol>
 *
 * <p>运行方式（仓库根目录，先启动 server-app；首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/spring-boot-server/client-app spring-boot:run
 * </pre>
 *
 * <p>演示完成后进程长驻（SSE 保持在线可继续收发），Ctrl+C 优雅关闭。</p>
 *
 * @author lianghang
 */
@SpringBootApplication
public class ClientDemoApplication {

    private static final String SERVER_BASE = System.getProperty("demo.server-base", "http://127.0.0.1:8082");
    private static final String CLIENT_ID = "demo-client";

    public static void main(String[] args) {
        SpringApplication.run(ClientDemoApplication.class, args);
    }

    /**
     * 业务演示：依赖注入的 {@link LinkoraClient} 已由 starter 启动。
     *
     * @param client starter 自动装配的客户端
     * @param orderStatusService 上行：订单状态变更服务
     * @param reviewReplyService 上行：评价回复服务
     */
    @Bean
    public CommandLineRunner linkoraDemo(LinkoraClient client,
                                         OrderStatusService orderStatusService,
                                         ReviewReplyService reviewReplyService) {
        return args -> {
            try {
                runDemo(client, orderStatusService, reviewReplyService);
            } catch (Exception e) {
                // 演示流程失败不阻断应用启动（如 server 未启动）：
                // 客户端长驻运行，REST 端点（/api/ship 等）仍可手动验证
                System.out.println("[提示] 内置演示流程未完成（server 未启动？）: " + e.getMessage());
                System.out.println("[提示] 客户端已长驻运行，可手动调用 REST 端点验证");
            }
        };
    }

    private void runDemo(LinkoraClient client,
                         OrderStatusService orderStatusService,
                         ReviewReplyService reviewReplyService) throws Exception {
        {
            CountDownLatch orderReceived = new CountDownLatch(1);
            CountDownLatch reviewReceived = new CountDownLatch(1);

            // 1. 注册两个下行消息处理器（各管一种通知，收到后打印并 ackProcessed）
            client.addListener(OrderCreatedHandler.TOPIC, new OrderCreatedHandler(orderReceived));
            client.addListener(ReviewCreatedHandler.TOPIC, new ReviewCreatedHandler(reviewReceived));
            System.out.println("[就绪] 已注册 2 个下行处理器（order-created / review-created），SSE 在线");

            // 2. 上行一：线下更改订单状态 → 通知线上
            String mid1 = orderStatusService.updateStatus("SO-1001", "SHIPPED");
            boolean up1 = orderStatusService.awaitServerAccepted(mid1, 30_000);
            System.out.println("[上行-订单状态] SO-1001 → SHIPPED "
                    + (up1 ? "服务端已接受(SERVER_ACCEPTED)" : "接受超时(见日志)"));

            // 3. 上行二：线下回复评价 → 通知线上
            String mid2 = reviewReplyService.reply("RV-2001", "感谢反馈，已补发优惠券");
            boolean up2 = reviewReplyService.awaitServerAccepted(mid2, 30_000);
            System.out.println("[上行-评价回复] RV-2001 ← 感谢反馈，已补发优惠券 "
                    + (up2 ? "服务端已接受(SERVER_ACCEPTED)" : "接受超时(见日志)"));

            // 4. 触发线上下单 → 通知线下（也可手动 curl 触发），经 SSE 推回本端
            int c1 = httpPost(SERVER_BASE + "/api/orders?clientId=" + enc(CLIENT_ID)
                    + "&orderNo=SO-2002&item=" + enc("机械键盘 x1"));
            System.out.println("[触发] POST /api/orders → HTTP " + c1 + "，等待新订单通知…");

            // 5. 触发线上评价 → 通知线下
            int c2 = httpPost(SERVER_BASE + "/api/reviews?clientId=" + enc(CLIENT_ID)
                    + "&reviewId=RV-3001&content=" + enc("包装完好，五星好评"));
            System.out.println("[触发] POST /api/reviews → HTTP " + c2 + "，等待新评价通知…");

            // 6. 等待两条下行通知都到达
            boolean down1 = orderReceived.await(30, TimeUnit.SECONDS);
            boolean down2 = reviewReceived.await(30, TimeUnit.SECONDS);
            System.out.println((up1 && up2 && down1 && down2)
                    ? "[PASS] 四向闭环完成：2 条上行被服务端处理，2 条下行通知均收到并 ACK"
                    : "[FAIL] 闭环未完成（up1=" + up1 + " up2=" + up2
                    + " down1=" + down1 + " down2=" + down2
                    + "，确认 server-app 已启动且端口未被占用）");

            System.out.println("========================================");
            System.out.println("内置四向闭环演示完成，客户端长驻运行");
            System.out.println("========================================");

            // 7. 顺序键演示（上行侧）：同一订单连发三条状态 + 另一订单并行。
            //    期望线上按 PAID → SHIPPED → SIGNED 顺序处理（SO-9002 并行不受影响）；
            //    服务端日志与 GET /api/uplink/order-status-updates 可核对到达顺序。
            orderStatusService.updateStatus("SO-9001", "PAID");
            orderStatusService.updateStatus("SO-9002", "PAID");
            orderStatusService.updateStatus("SO-9001", "SHIPPED");
            orderStatusService.updateStatus("SO-9001", "SIGNED");
            System.out.println("[顺序键演示] 已连发上行：SO-9001 PAID→SHIPPED→SIGNED"
                    + "（同键必须按序到达），SO-9002 PAID（不同键并行）");
        }
    }

    /** URL 编码（UTF-8）。 */
    private static String enc(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }

    /** POST 空体请求，返回 HTTP 状态码（参数已拼在 query 上）。 */
    private static int httpPost(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
