package com.linkora.example.client;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.LinkoraReceivedMessage;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.error.LinkoraErrorCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 普通 Java 客户端示例：零 Spring 依赖。SQLite 本地队列 + SSE 下行 +
 * POST 上行 + ACK 流程全部由 LinkoraClient 编排。
 *
 * <p>运行方式（仓库根目录，先启动 plain-java-server 示例；首次需先
 * {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/plain-java-client exec:java ^
 *       -Dexec.mainClass=com.linkora.example.client.PlainJavaClientMain ^
 *       -Dexec.args="http://127.0.0.1:8080 demo-client"
 * </pre>
 *
 * <p>HMAC 认证接入：将 {@code credentialProvider} 替换为
 * {@code new HmacCredentialProvider(clientId, accessToken, secretBytes)}
 * 并在服务端配置 {@code HmacClientAuthenticator}，见 docs/security.md。</p>
 */
public final class PlainJavaClientMain {

    private static final String DEMO_TOPIC_GREETING = "greeting";
    private static final String DEMO_TOPIC_REPORT = "demo-report";

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

        // 3. 注册业务监听器：收到消息 → 打印 → 显式 ackProcessed（业务成功）
        client.addListener(DEMO_TOPIC_GREETING, new com.linkora.client.MessageListener() {
            @Override
            public void onMessage(LinkoraReceivedMessage message) {
                System.out.println("[下行] topic=" + message.getMessageType()
                        + " payload=" + message.getPayloadAsUtf8String()
                        + " id=" + message.getMessageId());
                // 业务处理成功后确认；失败场景用 message.ackFailed(LinkoraErrorCode.XXX, "原因")
                message.ackProcessed();
            }
        });

        client.start();
        System.out.println("========================================");
        System.out.println("Linkora 演示客户端已启动");
        System.out.println("  服务端: " + serverUrl);
        System.out.println("  客户端: " + clientId);
        System.out.println("  本地库: " + dbFile);
        System.out.println("========================================");

        // 4. 上行演示：先写 SQLite outbox，后台线程 POST 并等待服务端接受
        for (int i = 1; i <= 3; i++) {
            String body = "demo-report-" + i;
            String messageId = client.publish(DEMO_TOPIC_REPORT, body.getBytes(StandardCharsets.UTF_8));
            System.out.println("[上行] 已入队: " + body + " (messageId=" + messageId + ")");
        }

        // 5. Ctrl+C 优雅关闭（在途消息状态已持久化，重启自动续跑）
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                client.close();
                System.out.println("Linkora 演示客户端已关闭");
            }
        }, "linkora-demo-client-shutdown"));

        Thread.currentThread().join();
    }
}
