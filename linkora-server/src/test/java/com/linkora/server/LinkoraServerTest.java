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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * linkora-server 编排测试：真实 H2 JDBC 存储 + 记录型推送网关。
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
        assertEquals(-1, second.getSeq());
        assertEquals(1, server.getMessageStatus("fixed-id").orElse(null)
                .getDeliveryRecords().size());
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
