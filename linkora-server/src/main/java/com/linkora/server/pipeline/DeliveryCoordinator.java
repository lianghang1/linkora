package com.linkora.server.pipeline;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.serialize.jackson.MessageJsonCodec;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AuthRequest;
import com.linkora.server.auth.AuthResult;
import com.linkora.server.auth.ClientAuthenticator;
import com.linkora.server.servlet.SseClientConnection;
import com.linkora.server.servlet.SseSubscribeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 下行投递协调器：SSE 订阅处理 + 补发 + 重推调度 + 过期清理。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>推送只是"尽力写出"，写出成功 ≠ 客户端收到；唯一可靠信号是客户端 ACK，
 *       因此未完成消息会被周期性重推（指数节奏防风暴），客户端按 messageId 幂等去重；</li>
 *   <li>重连补发以客户端声明的 Last-Event-ID（seq）为起点，未声明时回退到
 *       服务端持久化游标；无论游标如何，周期扫描兜底覆盖一切未完成投递；</li>
 *   <li>过期消息停止投递并标记 EXPIRED（终态）；</li>
 *   <li>同一时刻只有一个执行线程调用本类写方法（由 LinkoraServer 的单线程执行器保证）。</li>
 * </ul>
 */
public final class DeliveryCoordinator implements SseSubscribeHandler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryCoordinator.class);

    /** 下行业务事件类型（与心跳事件 "hb" 区分）。 */
    public static final String MESSAGE_EVENT_TYPE = "msg";

    private final ClientAuthenticator authenticator;
    private final ClientRegistry clientRegistry;
    private final MessageRepository messageRepository;
    private final DeliveryRepository deliveryRepository;
    private final ClientCursorRepository cursorRepository;
    private final MessageJsonCodec codec;
    private final SsePushGateway gateway;
    private final ServerConfig config;
    private final Executor executor;

    /** 重推节奏表：key = clientId + '\u0001' + messageId。仅影响节奏，不影响正确性。 */
    private final ConcurrentHashMap<String, PushPacing> pacing =
            new ConcurrentHashMap<String, PushPacing>();

    public DeliveryCoordinator(ClientAuthenticator authenticator,
                               ClientRegistry clientRegistry,
                               MessageRepository messageRepository,
                               DeliveryRepository deliveryRepository,
                               ClientCursorRepository cursorRepository,
                               MessageJsonCodec codec,
                               SsePushGateway gateway,
                               ServerConfig config,
                               Executor executor) {
        if (authenticator == null || clientRegistry == null || messageRepository == null
                || deliveryRepository == null || cursorRepository == null
                || codec == null || gateway == null || config == null || executor == null) {
            throw new IllegalArgumentException("协调器依赖不能为 null");
        }
        this.authenticator = authenticator;
        this.clientRegistry = clientRegistry;
        this.messageRepository = messageRepository;
        this.deliveryRepository = deliveryRepository;
        this.cursorRepository = cursorRepository;
        this.codec = codec;
        this.gateway = gateway;
        this.config = config;
        this.executor = executor;
    }

    // ---------------- SseSubscribeHandler ----------------

    @Override
    public boolean onSubscribe(String clientId, String lastEventId,
                               Map<String, String> headers, SseClientConnection connection) {
        AuthResult auth = authenticator.authenticate(
                new AuthRequest(clientId, "GET", headers, null));
        if (!auth.isAuthenticated()) {
            log.warn("SSE 订阅认证失败: clientId={}, code={}", clientId,
                    auth.getErrorCode() == null ? "-" : auth.getErrorCode().getCode());
            return false;
        }
        com.linkora.core.storage.server.ClientRegistration registration =
                clientRegistry.findByClientId(clientId).orElse(null);
        if (registration == null) {
            log.warn("SSE 订阅拒绝：客户端未注册: clientId={}", clientId);
            return false;
        }
        if (!registration.isEnabled()) {
            log.warn("SSE 订阅拒绝：客户端已禁用: clientId={}", clientId);
            return false;
        }
        clientRegistry.updateConnectionState(clientId,
                com.linkora.core.storage.server.ConnectionState.CONNECTED, System.currentTimeMillis());
        long afterSeq = parseSeq(lastEventId);
        final String targetClient = clientId;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                backfill(targetClient, afterSeq);
            }
        });
        return true;
    }

    @Override
    public void onDisconnect(String clientId, SseClientConnection connection, Throwable cause) {
        // Servlet 已先从注册表注销该连接；若该客户端再无连接才标记离线
        if (gateway.connectionCount(clientId) == 0) {
            clientRegistry.updateConnectionState(clientId,
                    com.linkora.core.storage.server.ConnectionState.DISCONNECTED,
                    System.currentTimeMillis());
        }
        log.debug("SSE 连接断开: clientId={}", clientId);
    }

    // ---------------- 投递触发 ----------------

    /** 消息发布后立即尝试首次推送（绕过重推节奏）。 */
    public void pushNow(String clientId, String messageId, long seq) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                DeliveryRecord record = deliveryRepository.find(messageId, clientId).orElse(null);
                if (record != null) {
                    attemptPush(record, true);
                }
            }
        });
    }

    /** 周期扫描：向所有在线客户端补推未完成投递（含过期处理与到期重试）。 */
    public void sweep() {
        for (String clientId : gateway.activeClientIds()) {
            try {
                List<DeliveryRecord> records =
                        deliveryRepository.findUnfinishedAfterSeq(clientId, 0, config.getPushBatchSize());
                for (DeliveryRecord record : records) {
                    attemptPush(record, false);
                }
            } catch (RuntimeException e) {
                log.warn("补发扫描失败: clientId={}", clientId, e);
            }
        }
    }

    /** 重连补发：从客户端声明序号之后开始（绕过重推节奏，立即投递）。 */
    public void backfill(String clientId, long afterSeqExclusive) {
        try {
            List<DeliveryRecord> records = deliveryRepository.findUnfinishedAfterSeq(
                    clientId, afterSeqExclusive, config.getPushBatchSize());
            log.debug("重连补发: clientId={}, afterSeq={}, 待补发 {} 条",
                    clientId, afterSeqExclusive, records.size());
            for (DeliveryRecord record : records) {
                attemptPush(record, true);
            }
        } catch (RuntimeException e) {
            log.warn("重连补发失败: clientId={}", clientId, e);
        }
    }

    /** ACK 确认已收到/已处理时清理重推节奏。 */
    public void notifyDelivered(String messageId, String clientId) {
        pacing.remove(pacingKey(clientId, messageId));
    }

    // ---------------- 内部实现 ----------------

    private void attemptPush(DeliveryRecord record, boolean ignorePacing) {
        String messageId = record.getMessageId();
        String clientId = record.getClientId();
        try {
            LinkoraMessage message = messageRepository.findByMessageId(messageId).orElse(null);
            if (message == null) {
                log.warn("投递记录缺少消息正文，跳过推送: {}", record);
                return;
            }
            long now = System.currentTimeMillis();

            // 过期：停止投递并标记终态
            if (message.getExpiresAt() != null && message.getExpiresAt() < now) {
                if (markExpired(record)) {
                    pacing.remove(pacingKey(clientId, messageId));
                    log.info("消息已过期，标记 EXPIRED: messageId={}, clientId={}", messageId, clientId);
                }
                return;
            }

            MessageState state = record.getState();
            if (state.isTerminal()) {
                return;
            }
            // 到期重试判断
            if (isRetryState(state)) {
                Long nextRetryAt = record.getNextRetryAt();
                if (nextRetryAt != null && nextRetryAt > now) {
                    return;
                }
                if (state == MessageState.FAILED) {
                    // FAILED → RETRYING 后再推送；CAS 失败说明并发已推进，重读决定
                    boolean moved = deliveryRepository.compareAndSetState(messageId, clientId,
                            MessageState.FAILED, MessageState.RETRYING,
                            record.getLastError(), null, record.getRetryCount());
                    if (!moved) {
                        DeliveryRecord latest = deliveryRepository.find(messageId, clientId).orElse(null);
                        if (latest == null || latest.getState().isTerminal() || !isRetryState(latest.getState())) {
                            return;
                        }
                        if (latest.getState() == MessageState.FAILED) {
                            return; // CAS 失败且仍是 FAILED：由下一轮扫描再试
                        }
                    }
                }
            }

            if (!ignorePacing && state != MessageState.RETRYING
                    && !isPacingDue(clientId, messageId, now)) {
                return; // RETRYING 由重试退避调度，不受重推节奏约束
            }

            String data = codec.encodeMessage(message);
            int delivered = gateway.push(clientId, String.valueOf(record.getSeq()),
                    MESSAGE_EVENT_TYPE, data);
            recordPacing(clientId, messageId, now, delivered > 0);
            if (delivered > 0) {
                cursorRepository.saveLastDeliveredSeq(clientId, record.getSeq());
            }
        } catch (RuntimeException e) {
            log.warn("推送尝试失败: messageId={}, clientId={}", messageId, clientId, e);
        }
    }

    private static boolean isRetryState(MessageState state) {
        return state == MessageState.FAILED || state == MessageState.RETRYING;
    }

    private boolean markExpired(DeliveryRecord record) {
        String messageId = record.getMessageId();
        String clientId = record.getClientId();
        MessageState[] fromStates = {MessageState.CREATED, MessageState.ACCEPTED,
                MessageState.RECEIVED, MessageState.RETRYING, MessageState.FAILED};
        for (MessageState from : fromStates) {
            if (deliveryRepository.compareAndSetState(messageId, clientId,
                    from, MessageState.EXPIRED, "消息已过期", null, record.getRetryCount())) {
                return true;
            }
        }
        return false;
    }

    private boolean isPacingDue(String clientId, String messageId, long now) {
        PushPacing entry = pacing.get(pacingKey(clientId, messageId));
        return entry == null || now >= entry.nextDueAt;
    }

    private void recordPacing(String clientId, String messageId, long now, boolean delivered) {
        String key = pacingKey(clientId, messageId);
        PushPacing entry = pacing.get(key);
        int attempts = entry == null ? 0 : entry.attempts;
        long base = config.getPushPacingBaseMs();
        long delay;
        if (delivered) {
            delay = base; // 已写出但未确认：按固定节奏重推直至 ACK
        } else {
            long backoff = base;
            for (int i = 0; i < attempts && backoff < config.getPushPacingMaxMs(); i++) {
                backoff = Math.min(backoff * 2, config.getPushPacingMaxMs());
            }
            delay = backoff;
        }
        pacing.put(key, new PushPacing(now + delay, attempts + 1));
    }

    private static String pacingKey(String clientId, String messageId) {
        return clientId + '\u0001' + messageId;
    }

    /** 解析 Last-Event-ID 中的投递序号；不可解析时按 0 处理（全量补发）。 */
    static long parseSeq(String lastEventId) {
        if (lastEventId == null || lastEventId.trim().isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class PushPacing {
        final long nextDueAt;
        final int attempts;

        PushPacing(long nextDueAt, int attempts) {
            this.nextDueAt = nextDueAt;
            this.attempts = attempts;
        }
    }
}
