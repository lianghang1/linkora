package com.linkora.server.servlet;

import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.sqlite.SQLiteDataSource;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * 上行请求体上限必须来自 {@link ServerConfig#getMessageSizeLimitBytes()}，
 * Servlet 层不得再持有一份默认值。
 *
 * @author lianghang
 */
public class LinkoraEndpointServletBodyLimitTest {

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

    @Test
    public void bodyOverConfiguredLimitIsRejected() throws Exception {
        // 配 1KB：800 字节放行到业务层，1025 字节在 Servlet 层直接 413
        int inside = postWithLimit(1024L, 800);
        assertNotEquals("限内请求不应被 413 拦截", 413, inside);

        int outside = postWithLimit(1024L, 1025);
        assertEquals("超过配置上限应返回 413", 413, outside);
    }

    @Test
    public void largeConfiguredLimitIsNotCappedAtTwoMegabytes() throws Exception {
        // 曾经的 bug：Servlet 写死 2MB，配置调到 4MB 也不生效。
        // 3MB 在 4MB 配置下应放行到业务层（返回 4xx 但绝不是 413）。
        int code = postWithLimit(4L * 1024 * 1024, 3 * 1024 * 1024);
        assertNotEquals("4MB 配置不应被任何硬编码的 2MB 上限截断", 413, code);
    }

    private static int postWithLimit(long limit, int bodySize) throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(sqliteUrl("body_limit_" + System.nanoTime()));
        JdbcServerSchema.migrate(ds);
        LinkoraServer peer = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new JdbcClientRegistry(ds))
                .messageRepository(new JdbcMessageRepository(ds))
                .deliveryRepository(new JdbcDeliveryRepository(ds))
                .cursorRepository(new JdbcClientCursorRepository(ds))
                .config(ServerConfig.builder().messageSizeLimitBytes(limit).build())
                .build();
        peer.start();
        assertEquals("上限应与配置一致", limit, peer.getMaxBodyBytes());

        Server jetty = new Server(0);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        jetty.setHandler(context);
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(peer.ingestHttpHandler(), peer.getMaxBodyBytes())), "/messages");
        jetty.start();
        try {
            int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
            return post(port, body(bodySize));
        } finally {
            jetty.stop();
            peer.close();
        }
    }

    private static String body(int size) {
        StringBuilder sb = new StringBuilder("{\"messageId\":\"m-1\",\"pad\":\"");
        while (sb.length() < size) {
            sb.append('x');
        }
        sb.append("\"}");
        return sb.toString();
    }

    private static int post(int port, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("X-Linkora-Client-Id", "probe-client");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }
}
