package com.linkora.client;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.client.ClientStore;
import com.linkora.core.storage.client.InboxEntry;
import com.linkora.core.storage.client.OutboxEntry;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.server.LinkoraServer;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AllowAllClientAuthenticator;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 双端端到端验证：真实 Jetty 容器 + 服务端 H2 JDBC + 客户端 SQLite，
 * 覆盖上行摄入、下行推送、ACK 状态机与重复投递幂等。
 *
 * @author lianghang
 */
public class LinkoraClientEndToEndTest {

    private static final String CLIENT_ID = "store-e2e";

    private Server jetty;
    private LinkoraServer server;
    private JdbcDataSource dataSource;
    private SqliteClientStore clientStore;
    private LinkoraClient client;
    private final List<String> processedIds = new CopyOnWriteArrayList<String>();
    private final AtomicInteger listenerCalls = new AtomicInteger(0);
    private final AtomicInteger slowCalls = new AtomicInteger(0);
    private volatile boolean failProcessing = false;

    @Before
    public void setUp() throws Exception {
        // 服务端栈
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:linkora_e2e_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcServerSchema.migrate(dataSource);
        JdbcClientRegistry registry = new JdbcClientRegistry(dataSource);
        registry.register(new ClientRegistration(CLIENT_ID, true, System.currentTimeMillis()));
        server = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(registry)
                .messageRepository(new JdbcMessageRepository(dataSource))
                .deliveryRepository(new JdbcDeliveryRepository(dataSource))
                .cursorRepository(new JdbcClientCursorRepository(dataSource))
                .config(ServerConfig.builder()
                        .sweepIntervalMs(100)
                        .pushPacingBaseMs(100)
                        .pushPacingMaxMs(500)
                        .retryBackoffBaseMs(50)
                        .retryBackoffMaxMs(200)
                        .build())
                .build();
        server.start();

        jetty = new Server(0);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SECURITY);
        context.setContextPath("/");
        jetty.setHandler(context);
        context.addServlet(new ServletHolder(new SsePushServlet(
                server.sseSubscribeHandler(), server.sseConnectionRegistry())), "/linkora/events");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ingestHttpHandler())), "/linkora/messages");
        context.addServlet(new ServletHolder(
                new LinkoraEndpointServlet(server.ackHttpHandler())), "/linkora/acks");
        jetty.start();
        int port = ((ServerConnector) jetty.getConnectors()[0]).getLocalPort();

        // 客户端栈
        Path dbFile = Files.createTempDirectory("linkora-client-e2e").resolve("client.db");
        clientStore = new SqliteClientStore(dbFile);
        client = LinkoraClient.builder()
                .serverUrl("http://127.0.0.1:" + port + "/linkora")
                .clientId(CLIENT_ID)
                .clientStore(clientStore)
                .config(ClientConfig.builder()
                        .senderIntervalMs(100)
                        .recoveryIntervalMs(200)
                        .retryBackoffBaseMs(50)
                        .retryBackoffMaxMs(200)
                        .build())
                .build();
        // 慢监听器：不显式 ACK，靠"返回即自动 ACK"。用于验证恢复扫描重复派发时
        // 业务是否会被执行多次（单 worker 串行 + 处理完立即 PROCESSED，第二个任务应直接跳过）。
        client.addListener("SLOW_EVENT", new MessageListener() {
            @Override
            public void onMessage(LinkoraReceivedMessage message) {
                slowCalls.incrementAndGet();
                try {
                    Thread.sleep(600L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        client.addListener("BIZ_EVENT", new MessageListener() {
            @Override
            public void onMessage(LinkoraReceivedMessage message) {
                listenerCalls.incrementAndGet();
                if (failProcessing) {
                    message.ackFailed(com.linkora.core.error.LinkoraErrorCode.INTERNAL_ERROR, "模拟业务失败");
                    return;
                }
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
            // 忽略关闭异常
        }
    }

    @Test
    public void slowListenerRunsOnceDespiteRecoveryRescan() throws Exception {
        // recoveryIntervalMs=200ms，监听器耗时 600ms：恢复扫描会在处理中把它再派发一次。
        // 单 worker 串行 → 第二个任务必然在第一个 ACK 之后才执行 → 看到 PROCESSED 直接跳过。
        server.publish(CLIENT_ID, "SLOW_EVENT", "{\"n\":1}".getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 2500L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100L);
        }
        assertEquals("自动 ACK 下，重复派发的任务不得重复执行业务", 1, slowCalls.get());
    }

    // ---------------- 上行 ----------------

    @Test
    public void uplinkMessageReachesServerAndAccepted() throws Exception {
        String messageId = client.publish("STOCK_REPORT", "{\"qty\":9}".getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 10000;
        OutboxStatus status = null;
        while (System.currentTimeMillis() < deadline) {
            OutboxEntry entry = clientStore.findOutbox(messageId).orElse(null);
            assertNotNull(entry);
            status = entry.getStatus();
            if (status == OutboxStatus.SERVER_ACCEPTED) {
                break;
            }
            Thread.sleep(50);
        }
        assertEquals(OutboxStatus.SERVER_ACCEPTED, status);
        assertNotNull(server.getMessageStatus(messageId).orElse(null));
    }

    // ---------------- 下行 ----------------

    @Test
    public void downlinkMessageDeliveredProcessedAndAcked() throws Exception {
        com.linkora.server.PublishResult result =
                server.publish(CLIENT_ID, "BIZ_EVENT", "{\"order\":\"A1\"}".getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 10000;
        while (processedIds.size() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, processedIds.size());
        assertEquals(result.getMessageId(), processedIds.get(0));

        // 服务端最终 PROCESSED
        assertDeliveryState(result.getMessageId(), MessageState.PROCESSED, 10000);
        // 客户端本地记录完整
        InboxEntry inbox = clientStore.findInbox(result.getMessageId()).orElse(null);
        assertNotNull(inbox);
        assertEquals(com.linkora.core.message.ProcessStatus.PROCESSED, inbox.getProcessStatus());
        assertEquals(com.linkora.core.message.ReceiveStatus.RECEIVED_ACK_SENT, inbox.getReceiveStatus());
    }

    @Test
    public void downlinkFailureReportedToServer() throws Exception {
        failProcessing = true;
        try {
            com.linkora.server.PublishResult result =
                    server.publish(CLIENT_ID, "BIZ_EVENT", "boom".getBytes(StandardCharsets.UTF_8));
            long deadline = System.currentTimeMillis() + 10000;
            while (listenerCalls.get() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, listenerCalls.get());
            // 客户端 FAILED ACK → 服务端记录失败
            assertDeliveryState(result.getMessageId(), MessageState.FAILED, 10000);
            com.linkora.server.MessageDeliveryStatus status =
                    server.getMessageStatus(result.getMessageId()).orElse(null);
            assertEquals(1, status.getDeliveryRecords().get(0).getRetryCount());
            assertNotNull(status.getDeliveryRecords().get(0).getNextRetryAt());
        } finally {
            failProcessing = false;
        }
    }

    @Test
    public void duplicateRedeliveryStaysIdempotent() throws Exception {
        LinkoraMessage message = LinkoraMessage.builder()
                .messageId("dup-e2e-1").clientId(CLIENT_ID).messageType("BIZ_EVENT")
                .payload("{\"dup\":true}".getBytes(StandardCharsets.UTF_8))
                .createdAt(System.currentTimeMillis()).build();
        server.publish(message);
        assertDeliveryState(message.getMessageId(), MessageState.PROCESSED, 10000);
        assertEquals(1, listenerCalls.get());

        // 重复发布触发重投：客户端不重复分发业务，幂等补发 PROCESSED ACK
        server.publish(message);
        Thread.sleep(2000);
        assertEquals("重复投递不得再次分发业务", 1, listenerCalls.get());
        assertDeliveryState(message.getMessageId(), MessageState.PROCESSED, 5000);
    }

    // ---------------- 工具 ----------------

    private void assertDeliveryState(String messageId, MessageState expected, long timeoutMs)
            throws Exception {
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
}
