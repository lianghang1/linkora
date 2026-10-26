package com.linkora.adminconsole;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.pipeline.SsePushGateway;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.sqlite.SQLiteDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.DispatcherType;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 控制台端到端测试：内嵌 Jetty + 真实 H2 JDBC 存储 + 真实 LinkoraServer，
 * 经 {@link AdminConsoleInstaller} 装配（真实宿主路径），HTTP 断言鉴权与全部端点。
 *
 * @author lianghang
 */
public class AdminConsoleEndToEndTest {

    /** SQLite 文件库等价实现（替代 H2 内存库：按名称隔离的 temp 目录，JVM 退出自动清理）。 */
    private static String sqliteUrl(String name) {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("linkora-test-" + name);
            java.io.File f = dir.resolve("db.sqlite").toFile();
            f.deleteOnExit();
            dir.toFile().deleteOnExit();
            return "jdbc:sqlite:" + f.getAbsolutePath();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建测试库目录失败: " + name, e);
        }
    }

    private static final String CLIENT = "store-001";
    private static final String TOKEN = "it-console-token";

    private LinkoraServer server;
    private Server jetty;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setUp() throws Exception {
        javax.sql.DataSource dataSource = newDataSource();
        com.linkora.server.storage.jdbc.JdbcServerSchema.migrate(dataSource);
        ClientRegistry registry = new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource);
        registry.register(new ClientRegistration(CLIENT, true, System.currentTimeMillis()));

        server = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(registry)
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(new StubGateway())
                .config(ServerConfig.builder().sweepIntervalMs(60_000).build())
                .build();
        server.start();

        // 真实宿主路径：经 AdminConsoleInstaller 手动装配（与 MVC/纯 Servlet 宿主一致）
        jetty = new Server(0);
        ServletContextHandler ctx = new ServletContextHandler();
        ctx.setContextPath("/");
        AdminConsoleInstaller.register(ctx.getServletContext(), server, TOKEN);
        jetty.setHandler(ctx);
        jetty.start();
        port = jetty.getURI().getPort();
    }

    /**
     * 默认 H2；-Dlinkora.it.mysql=true 时切换真实 MySQL（每次清空 lk_* 表），
     * 用于验证控制台全链路在 MySQL 存储上的行为。
     */
    private static javax.sql.DataSource newDataSource() throws Exception {
        SQLiteDataSource h2 = new SQLiteDataSource();
        h2.setUrl(sqliteUrl("linkora_admin_" + System.nanoTime()));
        if (!Boolean.getBoolean("linkora.it.mysql")) {
            return h2;
        }
        Class.forName("com.mysql.cj.jdbc.Driver");
        final String url = System.getProperty("linkora.it.mysql.url",
                "jdbc:mysql://127.0.0.1:3306/linkora_it_console?useSSL=false&allowPublicKeyRetrieval=true");
        final String user = System.getProperty("linkora.it.mysql.user", "root");
        final String pass = System.getProperty("linkora.it.mysql.password", "");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, pass);
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SHOW TABLES LIKE 'lk_%'")) {
            java.util.List<String> tables = new java.util.ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
            for (String t : tables) {
                st.executeUpdate("DROP TABLE IF EXISTS " + t);
            }
        }
        return new javax.sql.DataSource() {
            @Override
            public java.sql.Connection getConnection() throws java.sql.SQLException {
                return java.sql.DriverManager.getConnection(url, user, pass);
            }

            @Override
            public java.sql.Connection getConnection(String u, String p) throws java.sql.SQLException {
                return java.sql.DriverManager.getConnection(url, u, p);
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return java.util.logging.Logger.getGlobal();
            }
        };
    }

    @After
    public void tearDown() throws Exception {
        jetty.stop();
        server.close();
    }

    // ---------------- 鉴权 ----------------

    @Test
    public void missingTokenIsUnauthorized() throws Exception {
        HttpResult r = http("/linkora-admin/api/overview", null, "GET", null);
        assertEquals(401, r.status);
        assertTrue(r.body.contains("未授权"));
    }

    @Test
    public void wrongTokenIsUnauthorized() throws Exception {
        HttpResult r = http("/linkora-admin/api/overview", "bad-token", "GET", null);
        assertEquals(401, r.status);
    }

    @Test
    public void indexPageServedWithCorrectToken() throws Exception {
        HttpResult r = http("/linkora-admin/", TOKEN, "GET", null);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("Linkora 运维控制台"));
        assertTrue(r.body.contains("</html>"));
    }

    /** 页面本身必须无凭据可直达（浏览器首跳不带 Authorization 头），鉴权由页面内的 API 调用承担。 */
    @Test
    public void indexPageServedWithoutToken() throws Exception {
        HttpResult r = http("/linkora-admin/", null, "GET", null);
        assertEquals(200, r.status);
        assertTrue(r.body.contains("Linkora 运维控制台"));
        // API 仍然拦截
        assertEquals(401, http("/linkora-admin/api/overview", null, "GET", null).status);
    }

    // ---------------- 查询端点 ----------------

    @Test
    public void overviewContainsClientAndStats() throws Exception {
        publishOne("hello");
        HttpResult r = http("/linkora-admin/api/overview", TOKEN, "GET", null);
        assertEquals(200, r.status);
        JsonNode body = mapper.readTree(r.body);
        assertEquals(1, body.get("clients").size());
        assertEquals(CLIENT, body.get("clients").get(0).get("clientId").asText());
        assertTrue(body.has("publishedPerDay"));
        assertTrue(body.has("deliveryStateDistribution"));
    }

    @Test
    public void messagesListAndDetailRoundTrip() throws Exception {
        String messageId = publishOne("{\"k\":\"v\"}");
        HttpResult list = http("/linkora-admin/api/messages?clientId=" + CLIENT, TOKEN, "GET", null);
        assertEquals(200, list.status);
        JsonNode items = mapper.readTree(list.body);
        assertEquals(1, items.size());
        assertEquals(messageId, items.get(0).get("messageId").asText());
        // 列表必须携带真实投递状态（而非文字摘要猜测）
        assertEquals("ACCEPTED", items.get(0).get("state").asText());
        assertEquals("已接受，等待推送", items.get(0).get("stateText").asText());
        assertEquals(0, items.get(0).get("retryCount").asInt());
        assertTrue(items.get(0).has("lastError"));
        assertTrue(items.get(0).has("updatedAt"));

        HttpResult detail = http("/linkora-admin/api/messages/" + messageId, TOKEN, "GET", null);
        assertEquals(200, detail.status);
        JsonNode d = mapper.readTree(detail.body);
        assertEquals("{\"k\":\"v\"}", d.get("payload").asText());
        assertEquals(1, d.get("deliveries").size());
        assertEquals(MessageState.ACCEPTED.name(),
                d.get("deliveries").get(0).get("state").asText());

        HttpResult missing = http("/linkora-admin/api/messages/no-such", TOKEN, "GET", null);
        assertEquals(404, missing.status);
    }

    @Test
    public void deadLettersRequireClientId() throws Exception {
        HttpResult r = http("/linkora-admin/api/dead-letters", TOKEN, "GET", null);
        assertEquals(400, r.status);
        assertTrue(r.body.contains("clientId"));
    }

    /** 顺序键可视 + 同键阻塞统计：同键两条，第一条进死信后应报 1 条被阻塞。 */
    @Test
    public void sequenceKeyVisibleAndBlockedCounted() throws Exception {
        long now = System.currentTimeMillis();
        server.publish(com.linkora.core.message.LinkoraMessage.builder()
                .messageId("seq-key-a").clientId(CLIENT).messageType("BIZ")
                .payload("a".getBytes(StandardCharsets.UTF_8))
                .createdAt(now).sequenceKey("order-seq").build());
        server.publish(com.linkora.core.message.LinkoraMessage.builder()
                .messageId("seq-key-b").clientId(CLIENT).messageType("BIZ")
                .payload("b".getBytes(StandardCharsets.UTF_8))
                .createdAt(now).sequenceKey("order-seq").build());
        server.forceTransition(CLIENT, "seq-key-a", MessageState.DEAD_LETTER, "测试构造");

        HttpResult dead = http("/linkora-admin/api/dead-letters?clientId=" + CLIENT, TOKEN, "GET", null);
        assertTrue("死信视图应携带顺序键", dead.body.contains("order-seq"));

        HttpResult blocked = http("/linkora-admin/api/seq-blocked?clientId=" + CLIENT
                + "&messageId=seq-key-a", TOKEN, "GET", null);
        assertEquals(1, mapper.readTree(blocked.body).get("blocked").asInt());

        // 无顺序键的消息恒为 0
        String plain = server.publish(CLIENT, "BIZ", "plain".getBytes(StandardCharsets.UTF_8))
                .getMessageId();
        HttpResult noKey = http("/linkora-admin/api/seq-blocked?clientId=" + CLIENT
                + "&messageId=" + plain, TOKEN, "GET", null);
        assertEquals(0, mapper.readTree(noKey.body).get("blocked").asInt());
    }

    // ---------------- 写操作端点 ----------------

    @Test
    public void retryAndSkipDeadLetterViaConsole() throws Exception {
        String messageId = publishOne("dead");
        // 构造死信：人工状态变更 ACCEPTED → DEAD_LETTER（合法转移）
        server.forceTransition(CLIENT, messageId, MessageState.DEAD_LETTER, "测试构造死信");

        HttpResult list = http("/linkora-admin/api/dead-letters?clientId=" + CLIENT, TOKEN, "GET", null);
        assertEquals(1, mapper.readTree(list.body).size());

        // 重放：DEAD_LETTER → RETRYING
        HttpResult retry = http("/linkora-admin/api/actions/retry-dead-letter", TOKEN, "POST",
                "{\"clientId\":\"" + CLIENT + "\",\"messageId\":\"" + messageId
                        + "\",\"reason\":\"门店已线下确认\"}");
        assertEquals(200, retry.status);
        assertTrue(mapper.readTree(retry.body).get("ok").asBoolean());

        // 跳过：RETRYING → EXPIRED
        HttpResult skip = http("/linkora-admin/api/actions/skip", TOKEN, "POST",
                "{\"clientId\":\"" + CLIENT + "\",\"messageId\":\"" + messageId
                        + "\",\"reason\":\"重复推送\"}");
        assertEquals(200, skip.status);
        assertTrue(mapper.readTree(skip.body).get("ok").asBoolean());

        JsonNode detail = mapper.readTree(
                http("/linkora-admin/api/messages/" + messageId, TOKEN, "GET", null).body);
        assertEquals(MessageState.EXPIRED.name(),
                detail.get("deliveries").get(0).get("state").asText());
    }

    @Test
    public void batchSkipAndEnableDisableAndDisconnect() throws Exception {
        String first = publishOne("a");
        String second = publishOne("b");
        server.forceTransition(CLIENT, first, MessageState.DEAD_LETTER, "测试");
        server.forceTransition(CLIENT, second, MessageState.DEAD_LETTER, "测试");

        HttpResult batch = http("/linkora-admin/api/actions/batch-skip", TOKEN, "POST",
                "{\"clientId\":\"" + CLIENT + "\",\"reason\":\"批量清理\"}");
        assertEquals(200, batch.status);
        assertEquals(2, mapper.readTree(batch.body).get("count").asInt());

        assertEquals(true, mapper.readTree(http("/linkora-admin/api/actions/disable", TOKEN,
                "POST", "{\"clientId\":\"" + CLIENT + "\",\"reason\":\"维护窗口\"}").body).get("ok").asBoolean());
        assertEquals(true, mapper.readTree(http("/linkora-admin/api/actions/enable", TOKEN,
                "POST", "{\"clientId\":\"" + CLIENT + "\",\"reason\":\"维护结束\"}").body).get("ok").asBoolean());
        assertEquals(0, mapper.readTree(http("/linkora-admin/api/actions/disconnect", TOKEN,
                "POST", "{\"clientId\":\"" + CLIENT + "\",\"reason\":\"踢假在线\"}").body).get("count").asInt());
    }

    @Test
    public void registerAndRemoveClientLifecycle() throws Exception {
        // 注册：成功；重复注册：ok=false
        assertEquals(true, mapper.readTree(http("/linkora-admin/api/actions/register-client", TOKEN,
                "POST", "{\"newClientId\":\"store-999\",\"enabled\":true,\"reason\":\"新门店上线\"}")
                .body).get("ok").asBoolean());
        assertEquals(false, mapper.readTree(http("/linkora-admin/api/actions/register-client", TOKEN,
                "POST", "{\"newClientId\":\"store-999\",\"reason\":\"重复注册应拒绝\"}")
                .body).get("ok").asBoolean());
        // 注册后立即可用（出现在 overview）
        assertTrue(mapper.readTree(http("/linkora-admin/api/overview", TOKEN, "GET", null).body)
                .toString().contains("store-999"));
        // 缺 newClientId：400
        assertEquals(400, http("/linkora-admin/api/actions/register-client", TOKEN,
                "POST", "{\"reason\":\"缺ID\"}").status);
        // 注销：成功；再注销：ok=false
        assertEquals(true, mapper.readTree(http("/linkora-admin/api/actions/remove-client", TOKEN,
                "POST", "{\"clientId\":\"store-999\",\"reason\":\"门店撤点\"}")
                .body).get("ok").asBoolean());
        assertEquals(false, mapper.readTree(http("/linkora-admin/api/actions/remove-client", TOKEN,
                "POST", "{\"clientId\":\"store-999\",\"reason\":\"已不存在\"}")
                .body).get("ok").asBoolean());
    }

    @Test
    public void forceTransitionRejectsIllegalMove() throws Exception {
        String messageId = publishOne("x");
        // ACCEPTED → PROCESSED 为非法转移（跳过 RECEIVED/FAILED 链），应 400
        HttpResult r = http("/linkora-admin/api/actions/force-transition", TOKEN, "POST",
                "{\"clientId\":\"" + CLIENT + "\",\"messageId\":\"" + messageId
                        + "\",\"target\":\"PROCESSED\",\"reason\":\"误操作校验\"}");
        assertEquals(400, r.status);
    }

    @Test
    public void clientCommandChannel() throws Exception {
        HttpResult ping = http("/linkora-admin/api/actions/client-command", TOKEN, "POST",
                "{\"clientId\":\"" + CLIENT + "\",\"command\":\"PING\",\"reason\":\"连通性探测\"}");
        assertEquals(200, ping.status);
        assertEquals(0, mapper.readTree(ping.body).get("delivered").asInt()); // 离线 = 0

        HttpResult query = http("/linkora-admin/api/actions/client-command", TOKEN, "POST",
                "{\"clientId\":\"" + CLIENT + "\",\"command\":\"QUERY_OUTBOX\",\"reason\":\"排查积压\"}");
        String queryId = mapper.readTree(query.body).get("queryId").asText();
        assertTrue(!queryId.isEmpty());

        HttpResult result = http("/linkora-admin/api/command-result/" + queryId, TOKEN, "GET", null);
        assertTrue(mapper.readTree(result.body).get("result").isNull());
    }

    @Test
    public void unknownEndpointIs404() throws Exception {
        assertEquals(404, http("/linkora-admin/api/nope", TOKEN, "GET", null).status);
        assertEquals(404, http("/linkora-admin/api/actions/nope", TOKEN, "POST", "{}").status);
    }

    // ---------------- 工具 ----------------

    private String publishOne(String payload) {
        return server.publish(CLIENT, "BIZ", payload.getBytes(StandardCharsets.UTF_8))
                .getMessageId();
    }

    private HttpResult http(String path, String token, String method, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod(method);
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = conn.getOutputStream();
            out.write(bytes);
            out.flush();
        }
        int status = conn.getResponseCode();
        InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while (in != null && (read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        conn.disconnect();
        return new HttpResult(status, new String(buffer.toByteArray(), StandardCharsets.UTF_8));
    }

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    /** 不出网记录型网关：离线语义（push/sendControl 返回 0）。 */
    private static class StubGateway implements SsePushGateway {
        @Override
        public int push(String clientId, String eventId, String eventType, String data) {
            return 0;
        }

        @Override
        public boolean isConnected(String clientId) {
            return false;
        }

        @Override
        public int connectionCount(String clientId) {
            return 0;
        }

        @Override
        public Set<String> activeClientIds() {
            return new HashSet<>();
        }

        @Override
        public void closeAll() {
        }

        @Override
        public int disconnectClient(String clientId) {
            return 0;
        }

        @Override
        public int sendControl(String clientId, String data) {
            return 0;
        }
    }
}
