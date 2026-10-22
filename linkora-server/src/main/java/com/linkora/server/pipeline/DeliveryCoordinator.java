package com.linkora.server.pipeline;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.ClientCursorRepository;
import com.linkora.core.storage.server.ClientRegistry;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.ExpirySweeper;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.core.storage.server.RetentionCleaner;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 下行投递协调器：SSE 订阅处理 + 补发 + 重推调度 + 过期清理。
 *
 * <p>核心不变量：</p>
 * <ul>
 *   <li>推送只是"尽力写出"，写出成功 ≠ 客户端收到；唯一可靠信号是客户端 ACK，
 *       因此未完成消息会被周期性重推（指数节奏防风暴），客户端按 messageId 幂等去重；</li>
 *   <li>重连补发以客户端声明的 Last-Event-ID（seq）为起点，未声明时按 0 全量补发；
 *       无论游标如何，周期扫描兜底覆盖一切未完成投递（游标仅作观测，不参与调度决策）；</li>
 *   <li>过期消息停止投递并标记 EXPIRED（终态）；死信（DEAD_LETTER）停止投递，
 *       待人工介入推进状态后自然恢复；</li>
 *   <li>过期推进与保留策略清理是"不依赖在线状态"的维护动作：仓储实现了
 *       {@link ExpirySweeper} / {@link RetentionCleaner} 时随扫描周期低频执行，
 *       否则跳过（自实现仓储可自行归档）；</li>
 *       <li>同一时刻只有一个执行线程调用本类写方法（由 LinkoraServer 的单线程执行器保证）。</li>
 * </ul>
 *
 * @author lianghang
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

    /** 维护动作（过期推进/清理）的最小执行间隔：不必跟着 5s 的推送节奏跑。 */
    private static final long MAINTENANCE_INTERVAL_MS = 60_000L;

    /** 可选能力：仓储未实现对应接口时为 null，维护动作整体跳过。 */
    private final ExpirySweeper expirySweeper;
    private final RetentionCleaner retentionCleaner;
    private volatile long lastMaintenanceAt = 0L;

    /**
     * 周期扫描的轮转游标（clientId → 上次扫到的 seq）。
     *
     * <p>没有它时每轮都从 seq=0 取前 N 条：只要前 N 条迟迟不 ACK（弱网、业务处理慢），
     * 后面的新消息永远挤不进批次。看到不足一批就回卷到 0，兼顾公平与"从头补齐"。</p>
     */
    private final ConcurrentHashMap<String, Long> sweepCursor =
            new ConcurrentHashMap<String, Long>();

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
        this.expirySweeper = deliveryRepository instanceof ExpirySweeper
                ? (ExpirySweeper) deliveryRepository : null;
        this.retentionCleaner = deliveryRepository instanceof RetentionCleaner
                ? (RetentionCleaner) deliveryRepository : null;
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
        // 全部校验通过：置位推送就绪（先注册后认证时序下防止未认证连接收到业务数据），
        // 随后提交的补发首推即可命中本连接
        connection.markReadyForPush();
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

    /**
     * 周期扫描：向所有在线客户端补推未完成投递（含到期重试），并执行维护动作。
     *
     * <p>每轮从上次扫到的 seq 之后继续，扫到不足一批就回卷到 0——保证"前面一直不 ACK
     * 的历史消息"不会永久挡住后面的新消息。维护动作（离线过期推进 + 保留策略清理）
     * 按固定间隔低频执行，不依赖客户端是否在线。</p>
     */
    public void sweep() {
        long now = System.currentTimeMillis();
        Set<String> active = gateway.activeClientIds();
        for (String clientId : active) {
            try {
                Long cursor = sweepCursor.get(clientId);
                long afterSeq = cursor == null ? 0L : cursor.longValue();
                List<DeliveryRecord> records = deliveryRepository.findUnfinishedAfterSeq(
                        clientId, afterSeq, now, config.getPushBatchSize());
                for (DeliveryRecord record : records) {
                    attemptPush(record, false);
                }
                if (records.size() < config.getPushBatchSize()) {
                    sweepCursor.remove(clientId); // 已扫到尾，下一轮从头开始
                } else {
                    sweepCursor.put(clientId, records.get(records.size() - 1).getSeq());
                }
            } catch (RuntimeException e) {
                log.warn("补发扫描失败: clientId={}", clientId, e);
            }
        }
        // 已离线的客户端不再保留游标，避免长期运行后无界增长
        sweepCursor.keySet().retainAll(active);
        runMaintenance();
    }

    /**
     * 维护动作：先把"已过期但客户端一直离线"的记录推进到 EXPIRED，
     * 再按保留期清理终态记录与孤儿正文。
     *
     * <p>两者互不阻塞：任一步失败只告警，下一轮继续。</p>
     */
    private void runMaintenance() {
        long now = System.currentTimeMillis();
        if (now - lastMaintenanceAt < MAINTENANCE_INTERVAL_MS) {
            return;
        }
        lastMaintenanceAt = now;
        if (expirySweeper != null) {
            try {
                int expired = expirySweeper.expireOverdue(now, config.getExpirySweepBatchSize());
                if (expired > 0) {
                    log.info("离线过期推进完成：{} 条投递记录标记为 EXPIRED", expired);
                }
            } catch (RuntimeException e) {
                log.warn("过期推进失败（下一轮继续）", e);
            }
        }
        if (retentionCleaner != null && config.isRetentionEnabled()) {
            try {
                long deadline = now - config.getRetentionMs();
                int purged = retentionCleaner.purgeSettledBefore(deadline, config.getRetentionBatchSize());
                if (purged > 0) {
                    log.info("保留策略清理完成：删除 {} 条终态投递记录（保留期 {} ms）",
                            purged, config.getRetentionMs());
                }
            } catch (RuntimeException e) {
                log.warn("保留策略清理失败（下一轮继续）", e);
            }
        }
    }

    /** 重连补发：从客户端声明序号之后开始（绕过重推节奏，立即投递）。 */
    public void backfill(String clientId, long afterSeqExclusive) {
        try {
            List<DeliveryRecord> records = deliveryRepository.findUnfinishedAfterSeq(
                    clientId, afterSeqExclusive, System.currentTimeMillis(),
                    config.getPushBatchSize());
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
            // 死信停止投递：终态查询虽会过滤，但快照可能滞后；读正文前早退省 IO，
            // 人工介入推进状态后由周期扫描自然恢复投递
            if (record.getState() == MessageState.DEAD_LETTER) {
                return;
            }
            // 顺序键兜底：SQL 已过滤，但快照可能滞后（前一条刚被 ACK、本条刚被并发推送），
            // 这里再挡一次。不影响正确性，只影响时序。
            if (record.getOrderingKey() != null
                    && deliveryRepository.hasEarlierUnsettled(clientId, record.getOrderingKey(),
                    record.getSeq())) {
                return;
            }
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
                            record.getLastError(), null,
                            record.getRetryCount(), record.getRetryCount());
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
                    from, MessageState.EXPIRED, "消息已过期", null,
                    record.getRetryCount(), record.getRetryCount())) {
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
