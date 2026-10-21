package com.linkora.example.mvcclient;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.MessageListener;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.example.mvcclient.handler.NotificationHandler;
import com.linkora.example.mvcclient.service.ReportService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 纯 Java 客户端示例（零 Spring 依赖）：对接 server-app（Spring MVC 服务端宿主）。
 * 业务代码分两层——handler/NotificationHandler 负责下行消息处理，
 * service/ReportService 负责上行发送，本类手动装配并串演示流程。
 *
 * <p>演示流程（对端默认 http://127.0.0.1:8081）：</p>
 * <ol>
 *   <li>注册下行监听器：收到服务端消息 → NotificationHandler 打印 → ackProcessed；</li>
 *   <li>上行发送：ReportService.publish 写入 SQLite outbox → 后台线程 POST →
 *       等待 SERVER_ACCEPTED；</li>
 *   <li>请求服务端业务接口 /api/notify 下发一条消息 → 监听器收到（SSE）→ ACK。</li>
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

    /** 下行通知 topic（server-app 的 NotifyService 以此类型发布）。 */
    private static final String TOPIC_NOTIFICATION = "notification";
    /** 启动欢迎消息 topic（客户端离线发布，上线后补投）。 */
    private static final String TOPIC_GREETING = "greeting";

    private static final String SERVER_BASE = "http://127.0.0.1:8081";
    private static final String CLIENT_ID = "demo-client";

    private ClientDemoMain() {
    }

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : SERVER_BASE;
        String clientId = args.length > 1 ? args[1] : CLIENT_ID;
        Path dbFile = Paths.get("data", "linkora-mvc-client.db").toAbsolutePath();

        CountDownLatch notificationReceived = new CountDownLatch(1);
        ClientStore store = new SqliteClientStore(dbFile);
        final LinkoraClient client = LinkoraClient.builder()
                .serverUrl(serverUrl)
                .clientId(clientId)
                .clientStore(store)
                .config(ClientConfig.builder().build())
                .build();
        ReportService reportService = new ReportService(client, store);

        // 1. 下行监听：收到消息 → NotificationHandler 打印 → 业务成功后 ackProcessed
        //    （失败场景用 message.ackFailed(LinkoraErrorCode.XXX, "原因")，服务端按策略重试）
        client.addListener(TOPIC_NOTIFICATION, new NotificationHandler(notificationReceived));
        client.addListener(TOPIC_GREETING, new MessageListener() {
            @Override
            public void onMessage(LinkoraReceivedMessage message) {
                // 服务端启动时的欢迎消息（客户端离线发布，上线后补投）
                System.out.println("[下行] " + message.getMessageType() + ": "
                        + message.getPayloadAsUtf8String());
                message.ackProcessed();
            }
        });

        client.start();
        System.out.println("========================================");
        System.out.println("Linkora 演示客户端已启动（纯 Java，零 Spring）");
        System.out.println("  服务端: " + serverUrl);
        System.out.println("  客户端: " + clientId);
        System.out.println("  本地库: " + dbFile);
        System.out.println("========================================");

        // 2. 上行发送：ReportService 先落 SQLite outbox（进程崩溃不丢），后台线程自动 POST
        for (int i = 1; i <= 3; i++) {
            String body = "mvc-client-report-" + i;
            String messageId = reportService.submit(body);
            boolean accepted = reportService.awaitServerAccepted(messageId, 30_000);
            System.out.println("[上行] " + body + " messageId=" + messageId
                    + " 服务端" + (accepted ? "已接受(SERVER_ACCEPTED)" : "接受超时(见日志)"));
        }

        // 3. 请求服务端业务接口下发一条消息（也可手动 curl 触发），经 SSE 推回本端
        int code = postNotify(serverUrl, clientId, "hello from mvc client");
        System.out.println("[触发] POST /api/notify → HTTP " + code
                + "，等待服务端下发经 SSE 推回…");
        boolean received = notificationReceived.await(30, TimeUnit.SECONDS);
        System.out.println(received
                ? "[PASS] 闭环完成：上行被服务端接受，服务端下发的消息经 SSE 收到并已 ACK"
                : "[FAIL] 30 秒内未收到服务端下发消息（确认 server-app 已启动且端口未被占用）");

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
