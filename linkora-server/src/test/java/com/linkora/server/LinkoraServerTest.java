package com.linkora.server;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.ClientRegistration;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.server.auth.AllowAllClientAuthenticator;
import com.linkora.server.pipeline.SsePushGateway;
import com.linkora.server.servlet.LinkoraHttpRequest;
import com.linkora.server.servlet.LinkoraHttpResult;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * linkora-server 编排测试：真实 H2 JDBC 存储 + 记录型推送网关。
 *
 * @author lianghang
 */
public class LinkoraServerTest {

    private JdbcDataSource dataSource;
    private LinkoraServer server;
    private RecordingGateway gateway;

    private static final String CLIENT = "store-001";

    @Before
    public void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:linkora_server_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        com.linkora.server.storage.jdbc.JdbcServerSchema.migrate(dataSource);
        ClientRegistry registry = new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource);
        registry.register(new ClientRegistration(CLIENT, true, System.currentTimeMillis()));
        gateway = new RecordingGateway();
        server = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(registry)
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().sweepIntervalMs(100).build())
                .build();
        server.start();
    }

    @After
    public void tearDown() {
        server.close();
    }

    // ---------------- 发布与首推 ----------------

    @Test
    public void publishPersistsFirstAndAttemptsPush() throws Exception {
        PublishResult result = server.publish(CLIENT, "ORDER_CREATED",
                "{\"order\":\"A1\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.isDuplicate());
        assertTrue(result.getSeq() >= 1);

        // 已持久化
        MessageDeliveryStatus status = server.getMessageStatus(result.getMessageId()).orElse(null);
        assertNotNull(status);
        assertEquals(1, status.getDeliveryRecords().size());
        DeliveryRecord record = status.getDeliveryRecords().get(0);
        assertEquals(MessageState.ACCEPTED, record.getState());
        assertEquals(CLIENT, record.getClientId());

        // 异步首推已到达网关
        RecordingGateway.Pushed pushed = awaitPush(result.getMessageId());
        assertEquals(CLIENT, pushed.clientId);
        assertEquals(String.valueOf(result.getSeq()), pushed.eventId);
        assertEquals("msg", pushed.eventType);
        LinkoraMessage decoded = new com.linkora.serialize.jackson.MessageJsonCodec()
                .decodeMessage(pushed.data);
        assertEquals(result.getMessageId(), decoded.getMessageId());
    }

    @Test
    public void publishUnregisteredClientRejected() {
        try {
            server.publish("ghost", "T", "x".getBytes(StandardCharsets.UTF_8));
            org.junit.Assert.fail();
        } catch (LinkoraException e) {
            assertEquals(LinkoraErrorCode.FORBIDDEN, e.getErrorCode());
        }
    }

    @Test
    public void duplicatePublishIsIdempotent() {
        LinkoraMessage message = LinkoraMessage.builder()
                .messageId("fixed-id").clientId(CLIENT).messageType("T")
                .payload("p").createdAt(System.currentTimeMillis()).build();
        PublishResult first = server.publish(message);
        PublishResult second = server.publish(message);
        assertFalse(first.isDuplicate());
        assertTrue(second.isDuplicate());
        // 重复发布返回真实 seq（与首次一致），不再是占位 -1
        assertEquals(first.getSeq(), second.getSeq());
        assertEquals(1, server.getMessageStatus("fixed-id").orElse(null)
                .getDeliveryRecords().size());
    }

    @Test
    public void orphanMessageRecoveredByIdempotentRepublish() throws Exception {
        // 模拟崩溃窗口：save 后、init 前进程崩溃 → 有消息、无投递记录
        LinkoraMessage message = LinkoraMessage.builder()
                .messageId("orphan-id").clientId(CLIENT).messageType("T")
                .payload("p").createdAt(System.currentTimeMillis()).build();
        PublishResult first = server.publish(message);
        try (java.sql.Connection conn = dataSource.getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DELETE FROM lk_delivery WHERE message_id = 'orphan-id'");
        }
        assertEquals(0, server.getMessageStatus("orphan-id").orElse(null)
                .getDeliveryRecords().size());

        // 发布方幂等重试同 messageId：应补建投递记录并触发推送
        PublishResult retried = server.publish(message);
        assertTrue(retried.isDuplicate());
        assertTrue("补建后应返回真实 seq", retried.getSeq() >= 1);

        MessageDeliveryStatus status = server.getMessageStatus("orphan-id").orElse(null);
        assertEquals(1, status.getDeliveryRecords().size());
        assertEquals(MessageState.ACCEPTED,
                status.getDeliveryRecords().get(0).getState());
        awaitPush("orphan-id");
    }

    // ---------------- 顺序键（sequenceKey） ----------------

    @Test
    public void sequenceKeyBlocksSecondPushUntilFirstProcessed() throws Exception {
        // 同键两条：第一条推送后不 ACK，第二条不得被推送（首推与周期扫描都拦下）
        gateway.connectedClients.set(1); // 模拟客户端在线：周期扫描才会跑
        PublishResult first = server.publish(CLIENT, "ORDER",
                "state=1".getBytes(StandardCharsets.UTF_8), "order-1");
        RecordingGateway.Pushed firstPush = awaitPush(first.getMessageId());
        awaitDeliveryState(first.getMessageId(), MessageState.ACCEPTED);

        // 推送的线路 JSON 必须携带 sequenceKey（客户端才能读到）
        assertEquals("order-1", new com.linkora.serialize.jackson.MessageJsonCodec()
                .decodeMessage(firstPush.data).getSequenceKey());

        PublishResult second = server.publish(CLIENT, "ORDER",
                "state=2".getBytes(StandardCharsets.UTF_8), "order-1");
        // 覆盖多轮周期扫描（sweepIntervalMs=100）
        Thread.sleep(400);
        assertEquals("同键第二条在前一条终结前不得推送（含扫描路径）",
                0, gateway.pushedFor(second.getMessageId()));

        // RECEIVED 不放行，只有 PROCESSED（终态）放行
        assertEquals(200, postAck(first.getMessageId(), "RECEIVED", null, null).getStatus());
        Thread.sleep(200);
        assertEquals("RECEIVED 不是终态，不应放行", 0, gateway.pushedFor(second.getMessageId()));

        assertEquals(200, postAck(first.getMessageId(), "PROCESSED", null, null).getStatus());
        RecordingGateway.Pushed secondPush = awaitPush(second.getMessageId());
        assertEquals("state=2", new com.linkora.serialize.jackson.MessageJsonCodec()
                .decodeMessage(secondPush.data).getPayloadAsUtf8String());
    }

    @Test
    public void sequenceKeyDeadLetterReleasesLane() throws Exception {
        // 方案 A：队头进死信 = 放弃该条，同键后续照常投递
        LinkoraServer retrier = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().maxDeliveryRetries(1).build())
                .build();
        retrier.start();
        try {
            PublishResult first = retrier.publish(CLIENT, "ORDER",
                    "state=1".getBytes(StandardCharsets.UTF_8), "order-1");
            awaitPush(first.getMessageId());
            assertEquals(200, postAckOn(retrier, first.getMessageId(), "FAILED",
                    "BIZ-1", "boom").getStatus());
            assertEquals(MessageState.DEAD_LETTER,
                    currentStateOn(retrier, first.getMessageId()));

            PublishResult second = retrier.publish(CLIENT, "ORDER",
                    "state=2".getBytes(StandardCharsets.UTF_8), "order-1");
            awaitPush(second.getMessageId());
        } finally {
            retrier.close();
        }
    }

    // ---------------- 管理/观测接口 ----------------

    @Test
    public void adminViewsAreHumanReadable() throws Exception {
        PublishResult result = server.publish(CLIENT, "ORDER",
                "{\"orderNo\":\"SO-1\",\"item\":\"机械键盘 x1\"}".getBytes(StandardCharsets.UTF_8),
                "SO-1");
        awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);

        List<com.linkora.server.admin.DeliveryView> views =
                server.listUnfinishedDeliveries(CLIENT, 100);
        assertEquals(1, views.size());
        com.linkora.server.admin.DeliveryView view = views.get(0);
        assertEquals(result.getMessageId(), view.getMessageId());
        assertEquals(CLIENT, view.getClientId());
        assertEquals("已接受，等待推送", view.getStateText());
        assertTrue("时间必须是 yyyy-MM-dd HH:mm:ss 格式",
                view.getCreatedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertTrue("payload 必须是解码后的原文（不得是 Base64）",
                view.getPayloadPreview().contains("机械键盘 x1"));
        assertEquals("无重试调度时显示占位符", "—", view.getNextRetryAt());
        assertEquals("—", view.getLastError());
    }

    @Test
    public void retryDeadLetterViaAdminApi() throws Exception {
        LinkoraServer retrier = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().maxDeliveryRetries(1).build())
                .build();
        retrier.start();
        try {
            PublishResult result = retrier.publish(CLIENT, "ORDER",
                    "payload".getBytes(StandardCharsets.UTF_8));
            awaitPush(result.getMessageId());
            assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                    "BIZ-1", "boom").getStatus());
            assertEquals(MessageState.DEAD_LETTER, currentStateOn(retrier, result.getMessageId()));

            // 死信列表：人类可读，原因在 lastError
            List<com.linkora.server.admin.DeliveryView> deadLetters =
                    retrier.listDeadLetters(CLIENT, 100);
            assertEquals(1, deadLetters.size());
            assertEquals("死信，等待人工介入", deadLetters.get(0).getStateText());
            assertEquals("BIZ-1: boom", deadLetters.get(0).getLastError());

            // 人工重放 → RETRYING → 立即重推
            assertTrue(retrier.retryDeadLetter(CLIENT, result.getMessageId()));
            awaitDeliveryStateOn(retrier, result.getMessageId(), MessageState.RETRYING, 5000);
            awaitPushCount(result.getMessageId(), 2);

            // 已不在死信状态，重复重放返回 false
            assertFalse(retrier.retryDeadLetter(CLIENT, result.getMessageId()));
        } finally {
            retrier.close();
        }
    }

    @Test
    public void skipMessageMarksExpiredAndReleases() throws Exception {
        PublishResult result = server.publish(CLIENT, "ORDER", "p".getBytes(StandardCharsets.UTF_8));
        awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);

        assertTrue(server.skipMessage(CLIENT, result.getMessageId()));
        assertEquals(MessageState.EXPIRED, currentState(result.getMessageId()));
        assertTrue("人工跳过必须留下可读标记",
                currentRecordOn(server, result.getMessageId()).getLastError().contains("人工跳过"));

        // 终态后不再出现在未完成列表，重复跳过返回 false
        assertEquals(0, server.listUnfinishedDeliveries(CLIENT, 100).size());
        assertFalse(server.skipMessage(CLIENT, result.getMessageId()));
    }

    @Test
    public void clientOverviewsContainRegisteredClient() {
        List<com.linkora.server.admin.ClientOverviewView> overviews = server.getClientOverviews();
        assertFalse(overviews.isEmpty());
        com.linkora.server.admin.ClientOverviewView self = null;
        for (com.linkora.server.admin.ClientOverviewView view : overviews) {
            if (CLIENT.equals(view.getClientId())) {
                self = view;
            }
        }
        assertNotNull("概览必须包含已注册客户端", self);
        assertTrue(self.isEnabled());
        assertTrue("注册时间必须是人类可读格式",
                self.getCreatedAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    public void cleanupOnceDisabledByDefault() {
        assertEquals(0, server.cleanupOnce());
    }

    @Test
    public void cleanupOnceRemovesExpiredTerminalAndExemptsDeadLetter() throws Exception {
        java.sql.Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        c.commit();
        c.close();

        LinkoraServer cleaner = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(com.linkora.server.ServerConfig.builder()
                        .maxDeliveryRetries(1).retentionDays(1).build())
                .build();
        cleaner.start();
        try {
            // EXPIRED 终态（旧）：应被清理
            PublishResult expired = cleaner.publish(CLIENT, "ORDER",
                    "e".getBytes(StandardCharsets.UTF_8));
            awaitPush(expired.getMessageId());
            assertTrue(cleaner.forceTransition(CLIENT, expired.getMessageId(),
                    MessageState.EXPIRED, "烟测过期"));
            // 死信（旧）：豁免保留
            PublishResult dead = cleaner.publish(CLIENT, "ORDER",
                    "d".getBytes(StandardCharsets.UTF_8));
            awaitPush(dead.getMessageId());
            assertEquals(200, postAckOn(cleaner, dead.getMessageId(), "FAILED",
                    "BIZ-9", "boom").getStatus());
            assertEquals(MessageState.DEAD_LETTER, currentStateOn(cleaner, dead.getMessageId()));

            // 把投递记录的 updated_at 与消息正文的 created_at 拨回 40 天前
            java.sql.Statement st = dataSource.getConnection().createStatement();
            st.executeUpdate("UPDATE lk_delivery SET updated_at = "
                    + (System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
                    + " WHERE message_id IN ('" + expired.getMessageId() + "', '"
                    + dead.getMessageId() + "')");
            st.executeUpdate("UPDATE lk_message SET created_at = "
                    + (System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000)
                    + " WHERE message_id = '" + expired.getMessageId() + "'");
            st.close();

            int removed = cleaner.cleanupOnce();
            assertTrue("至少清理 1 条投递记录 + 1 条消息正文", removed >= 2);
            assertEquals("超期消息应整体清理（投递记录 + 正文）",
                    java.util.Optional.empty(), cleaner.getMessageStatus(expired.getMessageId()));
            assertEquals("死信必须豁免", MessageState.DEAD_LETTER,
                    currentStateOn(cleaner, dead.getMessageId()));
        } finally {
            cleaner.close();
        }
    }

    @Test
    public void disconnectClientDelegatesToGateway() {
        gateway.connectedClients.set(1);
        assertEquals(1, server.disconnectClient(CLIENT));
        assertEquals(1, gateway.disconnectCalls.get());
    }

    // ---------------- 管理/观测接口（第二批） ----------------

    @Test
    public void listMessagesFiltersPaginatesAndSummarizesDeliveries() throws Exception {
        server.publish(CLIENT, "order-created", "订单A".getBytes(StandardCharsets.UTF_8), "SO-A");
        server.publish(CLIENT, "review-created", "评价B".getBytes(StandardCharsets.UTF_8));

        // 按类型过滤
        List<com.linkora.server.admin.MessageView> orders = server.listMessages(
                CLIENT, "order-created", null, null, 1, 20);
        assertEquals(1, orders.size());
        assertEquals("订单A", orders.get(0).getPayloadPreview());
        assertEquals("SO-A", orders.get(0).getSequenceKey());

        // 投递状态摘要为中文
        assertTrue("摘要应包含中文状态", orders.get(0).getDeliverySummary().contains("等待推送"));

        // 分页：size=1 第 1 页只回 1 条
        List<com.linkora.server.admin.MessageView> page1 = server.listMessages(
                CLIENT, null, null, null, 1, 1);
        assertEquals(1, page1.size());

        // 时间过滤（未来时间 = 无结果）
        assertEquals(0, server.listMessages(CLIENT, null,
                System.currentTimeMillis() + 3600_000L, null, 1, 20).size());
    }

    @Test
    public void messageDetailReturnsFullPayloadAndDeliveries() throws Exception {
        StringBuilder big = new StringBuilder("{\"note\":\"");
        for (int i = 0; i < 300; i++) {
            big.append("字");
        }
        big.append("\"}");
        PublishResult result = server.publish(CLIENT, "ORDER",
                big.toString().getBytes(StandardCharsets.UTF_8));
        awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);

        com.linkora.server.admin.MessageDetailView detail =
                server.getMessageDetail(result.getMessageId());
        assertNotNull(detail);
        assertEquals("详情 payload 必须完整（不截断）", big.length(), detail.getPayload().length());
        assertEquals(1, detail.getDeliveries().size());
        assertEquals(CLIENT, detail.getDeliveries().get(0).getClientId());
        assertNull(server.getMessageDetail("不存在"));
    }

    @Test
    public void forceTransitionValidatesAndApplies() throws Exception {
        PublishResult result = server.publish(CLIENT, "ORDER", "p".getBytes(StandardCharsets.UTF_8));
        awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);

        // 合法转移 1：ACCEPTED → RECEIVED
        assertTrue(server.forceTransition(CLIENT, result.getMessageId(),
                MessageState.RECEIVED, "线下已确认接收"));
        assertEquals(MessageState.RECEIVED, currentState(result.getMessageId()));

        // 合法转移 2：RECEIVED → PROCESSED（线下已手工处理）
        assertTrue(server.forceTransition(CLIENT, result.getMessageId(),
                MessageState.PROCESSED, "线下已手工处理"));
        assertEquals(MessageState.PROCESSED, currentState(result.getMessageId()));
        assertTrue("reason 必须留痕",
                currentRecordOn(server, result.getMessageId()).getLastError().contains("人工变更"));

        // 同状态 → false；终态再转移 → 非法异常；不存在的消息 → false
        assertFalse(server.forceTransition(CLIENT, result.getMessageId(),
                MessageState.PROCESSED, "重复"));
        try {
            server.forceTransition(CLIENT, result.getMessageId(),
                    MessageState.RETRYING, "想回退");
            fail("终态不允许再转移");
        } catch (IllegalArgumentException expected) {
        }
        assertFalse(server.forceTransition(CLIENT, "ghost", MessageState.PROCESSED, "r"));
    }

    @Test
    public void enableDisableClientRoundTrip() {
        assertTrue(server.disableClient(CLIENT));
        assertFalse(clientRegistry().findByClientId(CLIENT).get().isEnabled());
        assertTrue(server.enableClient(CLIENT));
        assertTrue(clientRegistry().findByClientId(CLIENT).get().isEnabled());
        assertFalse(server.disableClient("ghost"));
    }

    private com.linkora.core.storage.server.ClientRegistry clientRegistry() {
        return new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource);
    }

    @Test
    public void batchDeadLetterOperations() throws Exception {
        LinkoraServer retrier = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().maxDeliveryRetries(1).build())
                .build();
        retrier.start();
        try {
            for (int i = 1; i <= 2; i++) {
                PublishResult result = retrier.publish(CLIENT, "ORDER",
                        ("p" + i).getBytes(StandardCharsets.UTF_8));
                awaitPush(result.getMessageId());
                assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                        "BIZ-1", "boom").getStatus());
            }
            assertEquals(2, retrier.listDeadLetters(CLIENT, 100).size());

            // 批量重放：全部 → RETRYING
            assertEquals(2, retrier.retryAllDeadLetters(CLIENT, 100));
            assertEquals(0, retrier.listDeadLetters(CLIENT, 100).size());

            // 再造 2 条死信 → 批量跳过：全部 → EXPIRED
            for (int i = 3; i <= 4; i++) {
                PublishResult result = retrier.publish(CLIENT, "ORDER",
                        ("p" + i).getBytes(StandardCharsets.UTF_8));
                awaitPush(result.getMessageId());
                assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                        "BIZ-1", "boom").getStatus());
            }
            assertEquals(2, retrier.skipAllDeadLetters(CLIENT, 100));
            assertEquals(0, retrier.listDeadLetters(CLIENT, 100).size());
        } finally {
            retrier.close();
        }
    }

    @Test
    public void dailyStatsAreHumanReadable() throws Exception {
        PublishResult result = server.publish(CLIENT, "ORDER", "p".getBytes(StandardCharsets.UTF_8));
        awaitPush(result.getMessageId());
        assertEquals(200, postAck(result.getMessageId(), "PROCESSED", null, null).getStatus());

        com.linkora.server.admin.DailyStatsView stats = server.getDailyStats(7);
        assertEquals("今日发布量必须被统计到", 1L,
                stats.getPublishedPerDay().get(stats.getPublishedPerDay().size() - 1).getCount());
        assertTrue("状态分布必须含处理成功",
                stats.getDeliveryStateDistribution().containsKey("处理成功（终态）"));
        assertTrue("日期必须是 yyyy-MM-dd 格式",
                stats.getPublishedPerDay().get(0).getDate().matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    // ---------------- 管理指令通道（ctrl 事件，不落库） ----------------

    @Test
    public void sendClientCommandValidatesAndDeliversOnlyWhenOnline() {
        // 离线：返回 0，不抛异常
        gateway.connectedClients.set(0);
        assertEquals(0, server.pingClient(CLIENT));

        // 未知指令 → 拒绝
        try {
            server.sendClientCommand(CLIENT, "EXEC_ARBITRARY_CODE", null);
            fail("未知指令必须拒绝");
        } catch (IllegalArgumentException expected) {
        }

        // 在线：指令 JSON 送达网关
        gateway.connectedClients.set(1);
        assertEquals(1, server.retryClientOutboxDeadLetter(CLIENT, "msg-1"));
        assertEquals(1, gateway.controlPayloads.size());
        assertTrue("指令负载必须包含 cmd 与参数",
                gateway.controlPayloads.get(0).contains("RETRY_OUTBOX_DEAD_LETTER")
                        && gateway.controlPayloads.get(0).contains("msg-1"));
    }

    // ---------------- ACK 状态机 ----------------

    @Test
    public void receivedThenProcessedAckFlow() throws Exception {
        PublishResult result = server.publish(CLIENT, "T", "p".getBytes(StandardCharsets.UTF_8));
        awaitPush(result.getMessageId());
        awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);

        LinkoraHttpResult received = postAck(result.getMessageId(), "RECEIVED", null, null);
        assertEquals(200, received.getStatus());
        assertEquals(MessageState.RECEIVED, currentState(result.getMessageId()));

        // 幂等重复
        assertEquals(200, postAck(result.getMessageId(), "RECEIVED", null, null).getStatus());
        assertEquals(MessageState.RECEIVED, currentState(result.getMessageId()));

        assertEquals(200, postAck(result.getMessageId(), "PROCESSED", null, null).getStatus());
        assertEquals(MessageState.PROCESSED, currentState(result.getMessageId()));
        assertEquals(200, postAck(result.getMessageId(), "PROCESSED", null, null).getStatus());
        assertEquals(MessageState.PROCESSED, currentState(result.getMessageId()));
    }

    @Test
    public void processedAckFillsMissingReceivedForward() throws Exception {
        // 客户端 RECEIVED ACK 丢失：直接发 PROCESSED 应前向补推 RECEIVED 再推进
        PublishResult result = server.publish(CLIENT, "T", "p".getBytes(StandardCharsets.UTF_8));
        awaitPush(result.getMessageId());
        awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);
        assertEquals(200, postAck(result.getMessageId(), "PROCESSED", null, null).getStatus());
        assertEquals(MessageState.PROCESSED, currentState(result.getMessageId()));
    }

    @Test
    public void failedAckSchedulesRetryThenDeadLetters() throws Exception {
        LinkoraServer retrier = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder()
                        .maxDeliveryRetries(2)
                        .retryBackoffBaseMs(10)
                        .retryBackoffMaxMs(20)
                        .build())
                .build();
        retrier.start();
        try {
            gateway.connectedClients.set(1); // 网关在线：到期重试由扫描器推进 FAILED → RETRYING
            PublishResult result = retrier.publish(CLIENT, "T", "p".getBytes(StandardCharsets.UTF_8));
            awaitPush(result.getMessageId());
            awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);

            // 第一次失败 → FAILED + nextRetryAt
            assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                    "BIZ-1", "库存不足").getStatus());
            DeliveryRecord failed = currentRecordOn(retrier, result.getMessageId());
            assertEquals(MessageState.FAILED, failed.getState());
            assertEquals(1, failed.getRetryCount());
            assertNotNull(failed.getNextRetryAt());

            // 到期重试由协调器推进 FAILED → RETRYING → 重推
            awaitDeliveryStateOn(retrier, result.getMessageId(), MessageState.RETRYING, 5000);
            RecordingGateway.Pushed repush = awaitPushCount(result.getMessageId(), 2);
            assertNotNull(repush);

            // 第二次失败 → 超过 maxDeliveryRetries=2 → 死信
            assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                    "BIZ-1", "库存不足").getStatus());
            assertEquals(MessageState.DEAD_LETTER, currentStateOn(retrier, result.getMessageId()));
            // 幂等：再次 FAILED 不回退
            assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                    "BIZ-1", "again").getStatus());
            assertEquals(MessageState.DEAD_LETTER, currentStateOn(retrier, result.getMessageId()));
        } finally {
            retrier.close();
        }
    }

    @Test
    public void failedAckFromReceivedFillsForward() throws Exception {
        // FAILED ACK 在 RECEIVED 之前到达：先前向补推 RECEIVED，再合法转移 FAILED
        LinkoraServer retrier = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().maxDeliveryRetries(5).build())
                .build();
        retrier.start();
        try {
            PublishResult result = retrier.publish(CLIENT, "T", "p".getBytes(StandardCharsets.UTF_8));
            awaitPush(result.getMessageId());
            awaitDeliveryState(result.getMessageId(), MessageState.ACCEPTED);
            assertEquals(200, postAckOn(retrier, result.getMessageId(), "FAILED",
                    "X", "boom").getStatus());
            assertEquals(MessageState.FAILED, currentStateOn(retrier, result.getMessageId()));
        } finally {
            retrier.close();
        }
    }

    // ---------------- 过期与重推节奏 ----------------

    @Test
    public void sweepMarksExpiredAndSkipsPush() throws Exception {
        LinkoraServer expiring = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().sweepIntervalMs(100).build())
                .build();
        expiring.start();
        try {
            gateway.connectedClients.set(1); // 网关在线，扫描才会覆盖该客户端
            long now = System.currentTimeMillis();
            PublishResult result = expiring.publish(LinkoraMessage.builder()
                    .messageId("exp-1").clientId(CLIENT).messageType("T")
                    .payload("p").createdAt(now).expiresAt(now + 30L).build());
            // 客户端不在线（网关 0 连接），消息过期后扫描应标记 EXPIRED
            long deadline = System.currentTimeMillis() + 5000;
            MessageState state = null;
            while (System.currentTimeMillis() < deadline) {
                state = currentStateOn(expiring, result.getMessageId());
                if (state == MessageState.EXPIRED) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals(MessageState.EXPIRED, state);
        } finally {
            expiring.close();
        }
    }

    @Test
    public void sweepRepacesUndeliveredWithPacing() throws Exception {
        // 网关在线但 pushed 记录清空后：周期扫描应重推，且节奏窗口内不重复推
        server.sweepOnce();
        PublishResult result = server.publish(CLIENT, "T", "p".getBytes(StandardCharsets.UTF_8));
        awaitPush(result.getMessageId());
        int afterFirst = gateway.pushedFor(result.getMessageId());
        assertTrue(afterFirst >= 1);

        // 节奏窗口内（默认 base 5s）立即再扫描：不重推
        server.sweepOnce();
        assertEquals(afterFirst, gateway.pushedFor(result.getMessageId()));
    }

    // ---------------- 积压与状态查询 ----------------

    @Test
    public void backlogReflectsUnfinishedCount() throws Exception {
        assertEquals(0, server.getBacklog(CLIENT).getUnfinishedCount());
        server.publish(CLIENT, "T1", "p1".getBytes(StandardCharsets.UTF_8));
        PublishResult second = server.publish(CLIENT, "T2", "p2".getBytes(StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 5000;
        while (gateway.pushedFor(second.getMessageId()) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        BacklogInfo backlog = server.getBacklog(CLIENT);
        assertEquals(2, backlog.getUnfinishedCount());
        assertFalse(backlog.isConnected());

        LinkoraHttpResult received = postAck(second.getMessageId(), "RECEIVED", null, null);
        assertEquals(200, received.getStatus());
        // RECEIVED 仍属未完成（等待业务处理），完成 PROCESSED 后才消账
        assertEquals(2, server.getBacklog(CLIENT).getUnfinishedCount());
        assertEquals(200, postAck(second.getMessageId(), "PROCESSED", null, null).getStatus());
        assertEquals(1, server.getBacklog(CLIENT).getUnfinishedCount());
    }

    // ---------------- 上行摄入 ----------------

    @Test
    public void ingestAcceptsValidUplinkMessage() {
        LinkoraMessage message = LinkoraMessage.builder()
                .messageId("up-1").clientId(CLIENT).messageType("STOCK_REPORT")
                .payload("{\"qty\":3}").createdAt(System.currentTimeMillis()).build();
        com.linkora.serialize.jackson.MessageJsonCodec codec =
                new com.linkora.serialize.jackson.MessageJsonCodec();
        LinkoraHttpResult result = server.ingestHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", protocolHeaders(), codec.encodeMessage(message)
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, result.getStatus());
        String body = new String(result.getBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("ACCEPTED"));

        // 幂等重复
        LinkoraHttpResult duplicate = server.ingestHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", protocolHeaders(), codec.encodeMessage(message)
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, duplicate.getStatus());
        assertTrue(new String(duplicate.getBody(), StandardCharsets.UTF_8).contains("DUPLICATE"));
    }

    @Test
    public void ingestRejectsIdentityMismatchAndProtocolIssues() {
        com.linkora.serialize.jackson.MessageJsonCodec codec =
                new com.linkora.serialize.jackson.MessageJsonCodec();
        LinkoraMessage message = LinkoraMessage.builder()
                .messageId("up-2").clientId("other-client").messageType("T")
                .payload("p").createdAt(System.currentTimeMillis()).build();

        // 身份不匹配 → 403
        LinkoraHttpResult forbidden = server.ingestHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", protocolHeaders(), codec.encodeMessage(message)
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(403, forbidden.getStatus());

        // 缺协议头 → 412
        LinkoraMessage self = LinkoraMessage.builder()
                .messageId("up-3").clientId(CLIENT).messageType("T")
                .payload("p").createdAt(System.currentTimeMillis()).build();
        LinkoraHttpResult noProtocol = server.ingestHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", new LinkedHashMap<String, String>(),
                codec.encodeMessage(self).getBytes(StandardCharsets.UTF_8)));
        assertEquals(412, noProtocol.getStatus());

        // 协议版本不符 → 412
        Map<String, String> badVersion = protocolHeaders();
        badVersion.put("X-Linkora-Protocol", "99");
        LinkoraHttpResult wrongVersion = server.ingestHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", badVersion, codec.encodeMessage(self)
                .getBytes(StandardCharsets.UTF_8)));
        assertEquals(412, wrongVersion.getStatus());

        // 非法 JSON → 400
        LinkoraHttpResult badJson = server.ingestHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", protocolHeaders(), "{bad".getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, badJson.getStatus());
    }

    @Test
    public void ingestRejectsOversizeBody() {
        LinkoraServer strict = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .config(ServerConfig.builder().messageSizeLimitBytes(100).build())
                .build();
        strict.start();
        try {
            byte[] big = new byte[201];
            LinkoraHttpResult result = strict.ingestHttpHandler().handle(new LinkoraHttpRequest(
                    CLIENT, "POST", protocolHeaders(), big));
            assertEquals(413, result.getStatus());
        } finally {
            strict.close();
        }
    }

    @Test
    public void ingestListenerNotifiedAfterAcceptedUplink() {
        final List<LinkoraMessage> received = new CopyOnWriteArrayList<LinkoraMessage>();
        LinkoraServer listenerServer = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .ingestListener(new com.linkora.server.pipeline.IngestListener() {
                    @Override
                    public void onIngested(LinkoraMessage message) {
                        received.add(message);
                    }
                })
                .build();
        listenerServer.start();
        try {
            LinkoraMessage message = LinkoraMessage.builder()
                    .messageId("up-listener-1").clientId(CLIENT).messageType("STOCK_REPORT")
                    .payload("{\"qty\":3}").createdAt(System.currentTimeMillis()).build();
            com.linkora.serialize.jackson.MessageJsonCodec codec =
                    new com.linkora.serialize.jackson.MessageJsonCodec();
            LinkoraHttpResult result = listenerServer.ingestHttpHandler().handle(
                    new LinkoraHttpRequest(CLIENT, "POST", protocolHeaders(),
                            codec.encodeMessage(message).getBytes(StandardCharsets.UTF_8)));
            assertEquals(200, result.getStatus());
            assertEquals(1, received.size());
            assertEquals("up-listener-1", received.get(0).getMessageId());
            assertEquals("STOCK_REPORT", received.get(0).getMessageType());
        } finally {
            listenerServer.close();
        }
    }

    @Test
    public void ingestListenerFailureDoesNotBreakIngest() {
        LinkoraServer listenerServer = LinkoraServer.builder()
                .clientAuthenticator(new AllowAllClientAuthenticator())
                .clientRegistry(new com.linkora.server.storage.jdbc.JdbcClientRegistry(dataSource))
                .messageRepository(new com.linkora.server.storage.jdbc.JdbcMessageRepository(dataSource))
                .deliveryRepository(new com.linkora.server.storage.jdbc.JdbcDeliveryRepository(dataSource))
                .cursorRepository(new com.linkora.server.storage.jdbc.JdbcClientCursorRepository(dataSource))
                .pushGateway(gateway)
                .ingestListener(new com.linkora.server.pipeline.IngestListener() {
                    @Override
                    public void onIngested(LinkoraMessage message) {
                        throw new IllegalStateException("业务处理故障");
                    }
                })
                .build();
        listenerServer.start();
        try {
            LinkoraMessage message = LinkoraMessage.builder()
                    .messageId("up-listener-2").clientId(CLIENT).messageType("T")
                    .payload("p").createdAt(System.currentTimeMillis()).build();
            com.linkora.serialize.jackson.MessageJsonCodec codec =
                    new com.linkora.serialize.jackson.MessageJsonCodec();
            LinkoraHttpResult result = listenerServer.ingestHttpHandler().handle(
                    new LinkoraHttpRequest(CLIENT, "POST", protocolHeaders(),
                            codec.encodeMessage(message).getBytes(StandardCharsets.UTF_8)));
            assertEquals(200, result.getStatus());
            assertTrue(new String(result.getBody(), StandardCharsets.UTF_8)
                    .contains("ACCEPTED"));
        } finally {
            listenerServer.close();
        }
    }

    // ---------------- 补发 ----------------

    @Test
    public void backfillRespectsClientClaimedSeq() throws Exception {
        PublishResult first = server.publish(CLIENT, "T1", "p1".getBytes(StandardCharsets.UTF_8));
        PublishResult second = server.publish(CLIENT, "T2", "p2".getBytes(StandardCharsets.UTF_8));
        awaitPush(first.getMessageId());
        awaitPush(second.getMessageId());

        // 模拟客户端声明已收到 first.seq：补发只应包含之后的未完成消息（即 second）
        gateway.pushes.clear();
        com.linkora.server.pipeline.DeliveryCoordinator coordinator =
                (com.linkora.server.pipeline.DeliveryCoordinator) server.sseSubscribeHandler();
        coordinator.backfill(CLIENT, first.getSeq());
        long deadline = System.currentTimeMillis() + 3000;
        while (gateway.pushCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, gateway.pushCount());
        RecordingGateway.Pushed pushed = gateway.pushes.get(second.getMessageId()).get(0);
        assertEquals(String.valueOf(second.getSeq()), pushed.eventId);
    }

    // ---------------- 工具 ----------------

    private LinkoraHttpResult postAck(String messageId, String ackType, String errorCode, String errorMessage) {
        return postAckOn(server, messageId, ackType, errorCode, errorMessage);
    }

    private LinkoraHttpResult postAckOn(LinkoraServer target, String messageId,
                                        String ackType, String errorCode, String errorMessage) {
        com.linkora.serialize.jackson.MessageJsonCodec codec =
                new com.linkora.serialize.jackson.MessageJsonCodec();
        String body = codec.encodeAck(messageId,
                com.linkora.core.message.AckType.valueOf(ackType), errorCode, errorMessage);
        return target.ackHttpHandler().handle(new LinkoraHttpRequest(
                CLIENT, "POST", protocolHeaders(), body.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, String> protocolHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Linkora-Client-Id", CLIENT);
        headers.put("X-Linkora-Protocol", "1");
        return headers;
    }

    private MessageState currentState(String messageId) {
        return currentStateOn(server, messageId);
    }

    private MessageState currentStateOn(LinkoraServer target, String messageId) {
        return currentRecordOn(target, messageId).getState();
    }

    private DeliveryRecord currentRecordOn(LinkoraServer target, String messageId) {
        MessageDeliveryStatus status = target.getMessageStatus(messageId).orElse(null);
        assertNotNull(status);
        assertEquals(1, status.getDeliveryRecords().size());
        return status.getDeliveryRecords().get(0);
    }

    private DeliveryRecord awaitDeliveryState(String messageId, MessageState expected) throws Exception {
        return awaitDeliveryStateOn(server, messageId, expected, 5000);
    }

    private DeliveryRecord awaitDeliveryStateOn(LinkoraServer target, String messageId,
                                                MessageState expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        DeliveryRecord record = currentRecordOn(target, messageId);
        while (record.getState() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            record = currentRecordOn(target, messageId);
        }
        assertEquals(expected, record.getState());
        return record;
    }

    private RecordingGateway.Pushed awaitPush(String messageId) throws Exception {
        return awaitPushCount(messageId, 1);
    }

    private RecordingGateway.Pushed awaitPushCount(String messageId, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (gateway.pushedFor(messageId) < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        CopyOnWriteArrayList<RecordingGateway.Pushed> pushes = gateway.pushes.get(messageId);
        assertNotNull("等待推送超时: " + messageId, pushes);
        assertTrue("推送次数不足: " + gateway.pushedFor(messageId) + " < " + count,
                pushes.size() >= count);
        return pushes.get(pushes.size() - 1);
    }

    /** 记录型推送网关：push 恒定成功（模拟 1 条活动连接）。 */
    private static final class RecordingGateway implements SsePushGateway {
        final ConcurrentMap<String, CopyOnWriteArrayList<Pushed>> pushes =
                new ConcurrentHashMap<String, CopyOnWriteArrayList<Pushed>>();
        final AtomicLong connectedClients = new AtomicLong(0);

        static final class Pushed {
            final String clientId;
            final String messageId;
            final String eventId;
            final String eventType;
            final String data;

            Pushed(String clientId, String messageId, String eventId, String eventType, String data) {
                this.clientId = clientId;
                this.messageId = messageId;
                this.eventId = eventId;
                this.eventType = eventType;
                this.data = data;
            }
        }

        @Override
        public int push(String clientId, String eventId, String eventType, String data) {
            String messageId = new com.linkora.serialize.jackson.MessageJsonCodec()
                    .decodeMessage(data).getMessageId();
            CopyOnWriteArrayList<Pushed> list = pushes.get(messageId);
            if (list == null) {
                list = new CopyOnWriteArrayList<Pushed>();
                CopyOnWriteArrayList<Pushed> existing = pushes.putIfAbsent(messageId, list);
                if (existing != null) {
                    list = existing;
                }
            }
            list.add(new Pushed(clientId, messageId, eventId, eventType, data));
            return 1;
        }

        @Override
        public boolean isConnected(String clientId) {
            return connectedClients.get() > 0;
        }

        @Override
        public int connectionCount(String clientId) {
            return (int) connectedClients.get();
        }

        @Override
        public Set<String> activeClientIds() {
            return connectedClients.get() > 0
                    ? java.util.Collections.singleton("store-001")
                    : java.util.Collections.<String>emptySet();
        }

        @Override
        public void closeAll() {
            // no-op
        }

        final AtomicInteger disconnectCalls = new AtomicInteger(0);
        final java.util.List<String> controlPayloads =
                new java.util.concurrent.CopyOnWriteArrayList<String>();

        @Override
        public int disconnectClient(String clientId) {
            disconnectCalls.incrementAndGet();
            return isConnected(clientId) ? 1 : 0;
        }

        @Override
        public int sendControl(String clientId, String data) {
            if (isConnected(clientId)) {
                controlPayloads.add(data);
                return 1;
            }
            return 0;
        }

        int pushedFor(String messageId) {
            CopyOnWriteArrayList<Pushed> list = pushes.get(messageId);
            return list == null ? 0 : list.size();
        }

        int pushCount() {
            int total = 0;
            for (CopyOnWriteArrayList<Pushed> list : pushes.values()) {
                total += list.size();
            }
            return total;
        }
    }
}
