package com.linkora.example.mvcclient;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.example.mvcclient.handler.OrderCreatedHandler;
import com.linkora.example.mvcclient.handler.ReviewCreatedHandler;
import com.linkora.example.mvcclient.service.OrderStatusService;
import com.linkora.example.mvcclient.service.ReviewReplyService;

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
 * 纯 Java 客户端示例入口（线下门店侧，零 Spring 依赖）：对接 server-app
 * （Spring MVC 服务端宿主）。业务代码分两层——handler 包两个下行处理器
 * （新订单/新评价），service 包两个上行服务（订单状态/评价回复），
 * 本类手动装配并串演示流程。
 *
 * <p>演示闭环（对端默认 http://127.0.0.1:8081）：</p>
 * <ol>
 *   <li>下行一：线上下单（POST /api/orders）→ OrderCreatedHandler 收到并 ACK；</li>
 *   <li>下行二：线上评价（POST /api/reviews）→ ReviewCreatedHandler 收到并 ACK；</li>
 *   <li>上行一：线下更改订单状态 → 服务端 OrderStatusHandler 处理；</li>
 *   <li>上行二：线下回复评价 → 服务端 ReviewReplyHandler 处理。</li>
 * </ol>
 *
 * <p>运行方式（仓库根目录，先启动 server-app；首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/spring-mvc-server/client-app exec:java ^
 *       -Dexec.mainClass=com.linkora.example.mvcclient.ClientDemoMain
 * </pre>
 *
 * <p>HMAC 认证接入：将 {@code credentialProvider} 替换为
 * {@code new HmacCredentialProvider(clientId, accessToken, secretBytes)}，
 * 并在服务端配置 {@code HmacClientAuthenticator}，见 docs/security.md。</p>
 *
 * @author lianghang
 */
public final class ClientDemoMain {

    private static final String SERVER_BASE = "http://127.0.0.1:8081";
    private static final String CLIENT_ID = "demo-client";

    private ClientDemoMain() {
    }

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : SERVER_BASE;
        String clientId = args.length > 1 ? args[1] : CLIENT_ID;
        Path dbFile = Paths.get("data", "linkora-mvc-client.db").toAbsolutePath();

        CountDownLatch orderReceived = new CountDownLatch(1);
        CountDownLatch reviewReceived = new CountDownLatch(1);
        ClientStore store = new SqliteClientStore(dbFile);
        final LinkoraClient client = LinkoraClient.builder()
                .serverUrl(serverUrl)
                .clientId(clientId)
                .clientStore(store)
                .config(ClientConfig.builder().build())
                .build();
        OrderStatusService orderStatusService = new OrderStatusService(client, store);
        ReviewReplyService reviewReplyService = new ReviewReplyService(client, store);

        // 1. 注册两个下行消息处理器（各管一种通知，收到后打印并 ackProcessed）
        client.addListener(OrderCreatedHandler.TOPIC, new OrderCreatedHandler(orderReceived));
        client.addListener(ReviewCreatedHandler.TOPIC, new ReviewCreatedHandler(reviewReceived));

        client.start();
        System.out.println("========================================");
        System.out.println("Linkora 演示客户端已启动（纯 Java，零 Spring）");
        System.out.println("  服务端: " + serverUrl);
        System.out.println("  客户端: " + clientId);
        System.out.println("  本地库: " + dbFile);
        System.out.println("========================================");

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
        int c1 = httpPost(serverUrl + "/api/orders?clientId=" + enc(clientId)
                + "&orderNo=SO-2002&item=" + enc("机械键盘 x1"));
        System.out.println("[触发] POST /api/orders → HTTP " + c1 + "，等待新订单通知…");

        // 5. 触发线上评价 → 通知线下
        int c2 = httpPost(serverUrl + "/api/reviews?clientId=" + enc(clientId)
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

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                client.close();
                System.out.println("Linkora 演示客户端已关闭");
            }
        }, "linkora-mvc-client-shutdown"));

        System.out.println("演示完成，客户端长驻运行（Ctrl+C 优雅关闭）");
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
