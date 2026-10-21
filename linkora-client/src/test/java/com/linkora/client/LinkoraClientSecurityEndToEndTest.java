package com.linkora.client;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.message.MessageState;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.security.HmacSha256;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.CredentialStore;
import com.linkora.server.auth.HmacClientAuthenticator;
import com.linkora.server.servlet.LinkoraEndpointServlet;
import com.linkora.server.servlet.SsePushServlet;
import com.linkora.server.storage.jdbc.JdbcClientCursorRepository;
import com.linkora.server.storage.jdbc.JdbcClientRegistry;
import com.linkora.server.storage.jdbc.JdbcDeliveryRepository;
import com.linkora.server.storage.jdbc.JdbcMessageRepository;
import com.linkora.server.storage.jdbc.JdbcServerSchema;
import com.linkora.client.storage.sqlite.SqliteClientStore;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 安全世界端到端验证：HMAC 客户端 × HMAC 服务端认证（真实 Jetty + SQLite + H2）。
 *
 * @author lianghang
 */
public class LinkoraClientSecurityEndToEndTest {

    private static final String CLIENT_ID = "store-sec";
    private static final String TOKEN = "sec-token-abc";
    private static final byte[] SECRET = "sec-secret-key-xyz".getBytes(StandardCharsets.UTF_8);

    private Server jetty;
    private LinkoraServer server;
    private SqliteClientStore clientStore;
    private LinkoraClient client;
    private final List<String> processedIds = new CopyOnWriteArrayList<String>();

    @Before
    public void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:linkora_sec_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcServerSchema.migrate(dataSource);
        JdbcClientRegistry registry = new JdbcClientRegistry(dataSource);
        registry.register(new ClientRegistration(CLIENT_ID, true, System.currentTimeMillis()));
        server = LinkoraServer.builder()
                .clientAuthenticator(new HmacClientAuthenticator(new StaticCredentialStore()))
                .clientRegistry(registry)
                .messageRepository(new JdbcMessageRepository(dataSource))
                .deliveryRepository(new JdbcDeliveryRepository(dataSource))
                .cursorRepository(new JdbcClientCursorRepository(dataSource))
                .config(ServerConfig.builder().sweepIntervalMs(100).build())
                .build();
        server.start();

        jetty = new Server(0);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        jetty.setHandler(context);
        context.addServlet(new ServletHolder(new SsePushServlet(
                server.sseSubscribeHandler(), server.sseConnectionRegistry())), "/sec/events");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ingestHttpHandler())), "/sec/messages");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ackHttpHandler())), "/sec/acks");
        jetty.start();
        int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();

        Path dbFile = Files.createTempDirectory("linkora-sec").resolve("client.db");
        clientStore = new SqliteClientStore(dbFile);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port + "/sec")
                .clientId(CLIENT_ID)
                .clientStore(clientStore)
                .credentialProvider(new HmacCredentialProvider(CLIENT_ID, TOKEN, SECRET))
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        client.addListener("SEC_EVENT", new MessageListener() {
            @Override
            public void onMessage(LinkoraReceivedMessage message) {
                processedIds.add(message.getMessageId());
                message.ackProcessed();
            }
        });
        client.start();
    }

    @After
    public void tearDown() {
        client.close();
        clientStore.close();
        server.close();
        try {
            jetty.stop();
        } catch (Exception e) {
            // 忽略
        }
    }

    @Test
    public void signedClientCanUplinkAndDownlink() throws Exception {
        // 上行：签名请求被接受
        String messageId = client.publish("SEC_REPORT", "hello-sec".getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 10000;
        OutboxStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            OutboxEntry entry = clientStore.findOutbox(messageId).orElse(null);
            status = entry == null ? null : entry.getStatus();
            if (status == OutboxStatus.SERVER_ACCEPTED) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(OutboxStatus.SERVER_ACCEPTED, status);

        // 下行：SSE 订阅认证通过并收到消息，PROCESSED ACK 也通过认证
        com.linkora.server.PublishResult result =
                server.publish(CLIENT_ID, "SEC_EVENT", "push-sec".getBytes(StandardCharsets.UTF_8));
        deadline = System.currentTimeMillis() + 10000;
        while (processedIds.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, processedIds.size());
        assertEquals(result.getMessageId(), processedIds.get(0));
        assertState(result.getMessageId(), MessageState.PROCESSED, 10000);
    }

    @Test
    public void unsignedClientRejected() throws Exception {
        // 未签名客户端：SSE 订阅被 401，消息不被接受
        SqliteClientStore rogueStore = new SqliteClientStore(
                Files.createTempDirectory("linkora-rogue").resolve("rogue.db"));
        LinkoraClient rogue = LinkoraClient.builder()
                .serverUrl(clientEndpoint())
                .clientId(CLIENT_ID)
                .clientStore(rogueStore)
                .config(ClientConfig.builder().senderIntervalMs(100).recoveryIntervalMs(300).build())
                .build();
        try {
            rogue.start();
            String messageId = rogue.publish("STEAL", "bad".getBytes(StandardCharsets.UTF_8));
            // 服务器拒绝（401 不可重试）→ 客户端将消息推进到死信
            long deadline = System.currentTimeMillis() + 10000;
            OutboxStatus status = null;
            while (System.currentTimeMillis() < deadline) {
                OutboxEntry entry = rogueStore.findOutbox(messageId).orElse(null);
                status = entry == null ? null : entry.getStatus();
                if (status == OutboxStatus.DEAD_LETTER) {
                    break;
                }
                Thread.sleep(50);
            }
            assertEquals("未签名上报应进入死信（401 不可重试）", OutboxStatus.DEAD_LETTER, status);
        } finally {
            rogue.close();
            rogueStore.close();
        }
    }

    private String clientEndpoint() {
        // 复用 setUp 中 jetty 的端口：从 client 的 SSE URL 反推
        // 简化：直接重建（端口在 jetty.start 后固定）
        int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();
        return "http://127.0.0.1:" + port + "/sec";
    }

    private void assertState(String messageId, MessageState expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        MessageState state = null;
        while (System.currentTimeMillis() < deadline) {
            com.linkora.server.MessageDeliveryStatus status = server.getMessageStatus(messageId).orElse(null);
            assertNotNull(status);
            state = status.getDeliveryRecords().get(0).getState();
            if (state == expected) {
                return;
            }
            Thread.sleep(50);
        }
        assertEquals(expected, state);
    }

    /** 静态凭据库（测试用）。 */
    private static final class StaticCredentialStore implements CredentialStore {
        private final ConcurrentHashMap<String, CredentialRecord> records =
                new ConcurrentHashMap<String, CredentialRecord>();

        {
            records.put(CLIENT_ID, new CredentialRecord(CLIENT_ID, TOKEN, SECRET));
        }

        @Override
        public Optional<CredentialRecord> findByClientId(String clientId) {
            return Optional.ofNullable(records.get(clientId));
        }
    }
}
