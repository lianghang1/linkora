package com.linkora.example.client;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.example.client.handler.OrderCreatedHandler;
import com.linkora.example.client.handler.ReviewCreatedHandler;
import com.linkora.example.client.service.OrderStatusService;
import com.linkora.example.client.service.ReviewReplyService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 普通 Java 客户端示例（线下门店侧）：零 Spring 依赖。SQLite 本地队列 + SSE 下行 +
 * POST 上行 + ACK 流程全部由 LinkoraClient 编排。
 * 分层结构：handler 包两个下行处理器（新订单/新评价），service 包两个上行服务
 * （订单状态/评价回复，经 OutboxListener 回调等确认），本类只串演示流程。
 *
 * <p>演示闭环（对端为 plain-java-server 示例，默认 http://127.0.0.1:8080）：</p>
 * <ol>
 *   <li>上行一：线下更改订单状态 → 服务端 OrderStatusHandler 处理；</li>
 *   <li>上行二：线下回复评价 → 服务端 ReviewReplyHandler 处理；</li>
 *   <li>下行一：触发线上下单（POST /api/orders）→ OrderCreatedHandler 收到并 ACK；</li>
 *   <li>下行二：触发线上评价（POST /api/reviews）→ ReviewCreatedHandler 收到并 ACK。</li>
 * </ol>
 *
 * <p>运行方式（仓库根目录，先启动 plain-java-server 示例；首次需先
 * {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/plain-java-client compile exec:java ^
 *       -Dexec.mainClass=com.linkora.example.client.PlainJavaClientMain ^
 *       -Dexec.args="http://127.0.0.1:8080 demo-client"
 * </pre>
 *
 * <p>HMAC 认证接入：将 {@code credentialProvider} 替换为
 * {@code new HmacCredentialProvider(clientId, accessToken, secretBytes)}
 * 并在服务端配置 {@code HmacClientAuthenticator}，见 docs/security.md。</p>
 *
 * @author lianghang
 */
public final class PlainJavaClientMain {

    private PlainJavaClientMain() {
    }

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        String clientId = args.length > 1 ? args[1] : "demo-client";
        Path dbFile = Paths.get("data", "linkora-client-" + clientId + ".db").toAbsolutePath();

        // 1. SQLite 本地存储（WAL + synchronous=FULL，进程崩溃不丢消息）
        SqliteClientStore store = new SqliteClientStore(dbFile);

        // 2. 组装客户端（默认 NoCredentialProvider；HMAC 见 docs/security.md）
        final LinkoraClient client = LinkoraClient.builder()
                .serverUrl(serverUrl)
                .clientId(clientId)
                .clientStore(store)
                .config(ClientConfig.builder().build())
                .build();

        // 3. 业务分层装配：注册两个下行处理器 + 两个上行服务
        CountDownLatch orderReceived = new CountDownLatch(1);
        CountDownLatch reviewReceived = new CountDownLatch(1);
        client.addListener(OrderCreatedHandler.TOPIC, new OrderCreatedHandler(orderReceived));
        client.addListener(ReviewCreatedHandler.TOPIC, new ReviewCreatedHandler(reviewReceived));
        OrderStatusService orderStatusService = new OrderStatusService(client, store);
        ReviewReplyService reviewReplyService = new ReviewReplyService(client, store);

        client.start();
        System.out.println("========================================");
        System.out.println("Linkora 演示客户端已启动（线下门店侧）");
        System.out.println("  服务端: " + serverUrl);
        System.out.println("  客户端: " + clientId);
        System.out.println("  本地库: " + dbFile);
        System.out.println("========================================");

        // 4. 上行一：线下更改订单状态 → 通知线上
        String mid1 = orderStatusService.updateStatus("SO-1001", "SHIPPED");
        boolean up1 = orderStatusService.awaitServerAccepted(mid1, 30_000);
        System.out.println("[上行-订单状态] SO-1001 → SHIPPED "
                + (up1 ? "服务端已接受(SERVER_ACCEPTED)" : "接受超时(见日志)"));

        // 5. 上行二：线下回复评价 → 通知线上
        String mid2 = reviewReplyService.reply("RV-2001", "感谢反馈，已补发优惠券");
        boolean up2 = reviewReplyService.awaitServerAccepted(mid2, 30_000);
        System.out.println("[上行-评价回复] RV-2001 ← 感谢反馈，已补发优惠券 "
                + (up2 ? "服务端已接受(SERVER_ACCEPTED)" : "接受超时(见日志)"));

        // 6. 触发线上下单 → 通知线下（也可手动 curl 触发），经 SSE 推回本端
        int c1 = httpPost(serverUrl + "/api/orders?clientId=" + enc(clientId)
                + "&orderNo=SO-2002&item=" + enc("机械键盘 x1"));
        System.out.println("[触发] POST /api/orders → HTTP " + c1 + "，等待新订单通知…");

        // 7. 触发线上评价 → 通知线下
        int c2 = httpPost(serverUrl + "/api/reviews?clientId=" + enc(clientId)
                + "&reviewId=RV-3001&content=" + enc("包装完好，五星好评"));
        System.out.println("[触发] POST /api/reviews → HTTP " + c2 + "，等待新评价通知…");

        // 8. 等待两条下行通知都到达
        boolean down1 = orderReceived.await(30, TimeUnit.SECONDS);
        boolean down2 = reviewReceived.await(30, TimeUnit.SECONDS);
        System.out.println((up1 && up2 && down1 && down2)
                ? "[PASS] 四向闭环完成：2 条上行被服务端处理，2 条下行通知均收到并 ACK"
                : "[FAIL] 闭环未完成（up1=" + up1 + " up2=" + up2
                + " down1=" + down1 + " down2=" + down2
                + "，确认 server 已启动且端口未被占用）");

        // 9. Ctrl+C 优雅关闭（在途消息状态已持久化，重启自动续跑）
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                client.close();
                System.out.println("Linkora 演示客户端已关闭");
            }
        }, "linkora-demo-client-shutdown"));

        System.out.println("========================================");
        System.out.println("演示完成，客户端长驻运行（Ctrl+C 优雅关闭）");
        System.out.println("========================================");
        Thread.currentThread().join();
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
