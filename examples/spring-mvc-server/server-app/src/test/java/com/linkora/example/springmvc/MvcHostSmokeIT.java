package com.linkora.example.springmvc;

import com.linkora.client.ClientConfig;
import com.linkora.client.LinkoraClient;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.message.OutboxStatus;
import com.linkora.testkit.RecordingMessageListener;
import org.apache.catalina.startup.Tomcat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 宿主装配烟测（Spring MVC / 内嵌 Tomcat）：走真实 SpringMvcServerMain 装配路径
 * 启动完整服务，真实客户端经真实 SSE + HTTP 完成下行与上行往返。
 *
 * @author lianghang
 */
public class MvcHostSmokeIT {

    private static final String CLIENT_ID = "demo-client";

    private Tomcat tomcat;
    private int port;
    private LinkoraClient client;
    private SqliteClientStore store;

    @Before
    public void setUp() throws Exception {
        port = freePort();
        // 开启控制台（演示 AdminConsoleInstaller 在纯 MVC/手工注册 Tomcat 上的装配路径）
        System.setProperty("linkora.admin.console.token", "mvc-it-token");
        // IT 数据固定走 H2 内存库，避免污染远程 MySQL（AppConfig 默认连生产库）
        System.setProperty("linkora.datasource.url",
                "jdbc:h2:mem:linkora_mvc_it_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        tomcat = SpringMvcServerMain.startServer(port);
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (store != null) {
            store.close();
        }
        System.clearProperty("linkora.datasource.url");
        if (tomcat != null) {
            tomcat.stop();
        }
    }

    @Test(timeout = 120_000)
    public void fullRoundTripOnRealHost() throws Exception {
        // 1. 真实客户端（Spring 装配同款路径 + 临时 SQLite）
        Path db = Files.createTempDirectory("linkora-smoke").resolve("client.db");
        store = new SqliteClientStore(db);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port)
                .clientId(CLIENT_ID)
                .clientStore(store)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        RecordingMessageListener listener = new RecordingMessageListener();
        client.addListener("order-created", listener);
        client.start();

        // 2. 下行：走真实业务端点发布（订单号作顺序键）→ 真实 SSE → 客户端监听器收到
        int code = post("/api/orders?clientId=" + CLIENT_ID + "&orderNo=SMOKE-1&item=down");
        assertEquals(200, code);
        assertTrue("下行消息应在超时内送达", listener.awaitCount(1, 20_000));
        assertEquals("SMOKE-1", listener.messages().get(0).getPayloadAsUtf8String()
                .replaceAll(".*\"orderNo\":\"([^\"]+)\".*", "$1"));

        // 3. 上行：客户端发布 → 服务端接受并入库
        String uplinkId = client.publish("smoke-report", "up".getBytes(StandardCharsets.UTF_8));
        assertTrue("上行应被服务端确认接受", awaitOutboxAccepted(uplinkId, 20_000));
        assertTrue("服务端应已收到上行消息", awaitServerReceived("smoke-report", 20_000));
    }

    private int post(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(new byte[0]);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private boolean awaitOutboxAccepted(String messageId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (store.findOutbox(messageId).map(e -> e.getStatus() == OutboxStatus.SERVER_ACCEPTED)
                    .orElse(false)) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private boolean awaitServerReceived(String messageType, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port
                    + "/api/admin/messages?clientId=" + CLIENT_ID + "&messageType=" + messageType)
                    .openConnection();
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
            conn.disconnect();
            if (body.contains("smoke-report")) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** 控制台经 AdminConsoleInstaller 挂载在真实 Tomcat：鉴权、页面与端点全通。 */
    @Test(timeout = 60_000)
    public void adminConsoleViaInstallerOnTomcat() throws Exception {
        // 无令牌 → 401；页面本身放行（浏览器首跳无 Authorization 头）
        assertEquals(401, status("/linkora-admin/api/overview", null));
        assertEquals(401, status("/linkora-admin/api/overview", "wrong-token"));
        assertEquals(200, status("/linkora-admin/", null));

        // 正确令牌：页面直出 + overview 含注册客户端
        String page = get("/linkora-admin/", "mvc-it-token");
        assertTrue("控制台单页应正常直出", page.contains("Linkora 运维控制台"));
        String overview = get("/linkora-admin/api/overview", "mvc-it-token");
        assertTrue("总览应包含注册客户端", overview.contains(CLIENT_ID));
    }

    private int status(String path, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private String get(String path, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestProperty("Authorization", "Bearer " + token);
        String body = new String(readAll(conn.getInputStream()), StandardCharsets.UTF_8);
        conn.disconnect();
        return body;
    }

    /** JDK 8 兼容的流读取（readAllBytes 是 JDK 9+ API，项目最低 JDK 8）。 */
    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int r;
        while ((r = in.read(chunk)) != -1) {
            buf.write(chunk, 0, r);
        }
        in.close();
        return buf.toByteArray();
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
