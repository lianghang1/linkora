package com.linkora.server.pipeline;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.AckType;
import com.linkora.core.message.MessageState;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.serialize.jackson.MessageJsonCodec;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AuthRequest;
import com.linkora.server.auth.AuthResult;
import com.linkora.server.auth.ClientAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * ACK 处理服务（幂等）：
 * <ul>
 *   <li>RECEIVED：推进 ACCEPTED/CREATED → RECEIVED；重复 ACK 不回退已完成状态；</li>
 *   <li>PROCESSED：若接收 ACK 丢失则先补推 RECEIVED 再推进 PROCESSED；重复 ACK 幂等；</li>
 *   <li>FAILED：累计失败计数，未超阈值转 RETRYING（调度重投），超阈值进入死信；</li>
 *   <li>未知 messageId 一律返回 OK（消息可能已被保留策略清理），由服务端日志记录。</li>
 * </ul>
 *
 * @author lianghang
 */
public final class AckService {

    private static final Logger log = LoggerFactory.getLogger(AckService.class);

    private static final long MAX_ACK_BODY_BYTES = 64 * 1024L;

    private final ClientAuthenticator authenticator;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryCoordinator coordinator;
    private final ServerConfig config;

    public AckService(ClientAuthenticator authenticator,
                      DeliveryRepository deliveryRepository,
                      DeliveryCoordinator coordinator,
                      ServerConfig config) {
        if (authenticator == null || deliveryRepository == null
                || coordinator == null || config == null) {
            throw new IllegalArgumentException("AckService 依赖不能为 null");
        }
        this.authenticator = authenticator;
        this.deliveryRepository = deliveryRepository;
        this.coordinator = coordinator;
        this.config = config;
    }

    public AckResult ack(AuthRequest request) {
        AuthResult auth = authenticator.authenticate(request);
        if (!auth.isAuthenticated()) {
            return AckResult.rejected(
                    auth.getErrorCode() == null ? LinkoraErrorCode.AUTH_FAILED : auth.getErrorCode(),
                    auth.getMessage());
        }
        String protocolError = IngestService.checkProtocol(request);
        if (protocolError != null) {
            return AckResult.rejected(LinkoraErrorCode.UNSUPPORTED_PROTOCOL, protocolError);
        }
        if (request.getBodySize() > MAX_ACK_BODY_BYTES) {
            return AckResult.rejected(LinkoraErrorCode.INVALID_MESSAGE, "ACK 请求体过大");
        }
        MessageJsonCodec.WireAck wireAck;
        try {
            wireAck = new MessageJsonCodec().decodeAck(new String(request.getBody(), StandardCharsets.UTF_8));
        } catch (LinkoraException e) {
            return AckResult.rejected(LinkoraErrorCode.INVALID_MESSAGE, e.getMessage());
        }
        if (request.getClientId() == null) {
            return AckResult.rejected(LinkoraErrorCode.AUTH_REQUIRED, "缺少客户端身份");
        }
        String messageId = wireAck.getMessageId();
        String clientId = request.getClientId();
        DeliveryRecord record = deliveryRepository.find(messageId, clientId).orElse(null);
        if (record == null) {
            log.info("ACK 对应的投递记录不存在（可能已清理）: messageId={}, clientId={}, ackType={}",
                    messageId, clientId, wireAck.getAckType());
            return AckResult.ok();
        }
        try {
            switch (wireAck.getAckType()) {
                case RECEIVED:
                    return handleReceived(record);
                case PROCESSED:
                    return handleProcessed(record);
                case FAILED:
                    return handleFailed(record, wireAck);
                default:
                    return AckResult.rejected(LinkoraErrorCode.INVALID_MESSAGE, "未知 ACK 类型");
            }
        } finally {
            if (wireAck.getAckType() == AckType.RECEIVED || wireAck.getAckType() == AckType.PROCESSED) {
                coordinator.notifyDelivered(messageId, clientId);
            }
        }
    }

    private AckResult handleReceived(DeliveryRecord record) {
        String messageId = record.getMessageId();
        String clientId = record.getClientId();
        MessageState state = record.getState();
        if (state == MessageState.RECEIVED || state == MessageState.PROCESSED) {
            return AckResult.ok(); // 幂等：不能回退已完成状态
        }
        if (deliveryRepository.compareAndSetState(messageId, clientId,
                MessageState.ACCEPTED, MessageState.RECEIVED, null, null, record.getRetryCount())
                || deliveryRepository.compareAndSetState(messageId, clientId,
                MessageState.CREATED, MessageState.RECEIVED, null, null, record.getRetryCount())) {
            log.debug("客户端已持久化接收: messageId={}, clientId={}", messageId, clientId);
            return AckResult.ok();
        }
        DeliveryRecord latest = deliveryRepository.find(messageId, clientId).orElse(record);
        if (latest.getState() == MessageState.RECEIVED || latest.getState() == MessageState.PROCESSED) {
            return AckResult.ok();
        }
        // FAILED/RETRYING/EXPIRED/DEAD 状态下的迟到 RECEIVED ACK：不回退，幂等接受
        log.debug("迟到 RECEIVED ACK（当前状态 {}）: messageId={}, clientId={}",
                latest.getState(), messageId, clientId);
        return AckResult.ok();
    }

    private AckResult handleProcessed(DeliveryRecord record) {
        String messageId = record.getMessageId();
        String clientId = record.getClientId();
        MessageState state = record.getState();
        if (state == MessageState.PROCESSED) {
            return AckResult.ok();
        }
        // 接收 ACK 丢失时的前向补推：ACCEPTED/CREATED → RECEIVED（失败忽略，随后直接尝试 PROCESSED）
        if (state == MessageState.ACCEPTED) {
            deliveryRepository.compareAndSetState(messageId, clientId,
                    MessageState.ACCEPTED, MessageState.RECEIVED, null, null, record.getRetryCount());
        } else if (state == MessageState.CREATED) {
            deliveryRepository.compareAndSetState(messageId, clientId,
                    MessageState.CREATED, MessageState.RECEIVED, null, null, record.getRetryCount());
        }
        if (deliveryRepository.compareAndSetState(messageId, clientId,
                MessageState.RECEIVED, MessageState.PROCESSED, null, null, record.getRetryCount())) {
            log.debug("业务处理成功: messageId={}, clientId={}", messageId, clientId);
            return AckResult.ok();
        }
        DeliveryRecord latest = deliveryRepository.find(messageId, clientId).orElse(record);
        if (latest.getState() == MessageState.PROCESSED) {
            return AckResult.ok();
        }
        log.warn("PROCESSED ACK 状态冲突（当前 {}）: messageId={}, clientId={}",
                latest.getState(), messageId, clientId);
        return AckResult.rejected(LinkoraErrorCode.INTERNAL_ERROR,
                "当前状态 " + latest.getState() + " 不允许推进到 PROCESSED");
    }

    private AckResult handleFailed(DeliveryRecord record, MessageJsonCodec.WireAck wireAck) {
        String messageId = record.getMessageId();
        String clientId = record.getClientId();
        MessageState state = record.getState();
        if (state == MessageState.DEAD_LETTER || state == MessageState.EXPIRED
                || state == MessageState.PROCESSED) {
            return AckResult.ok(); // 终态不接受失败回退
        }
        int nextRetryCount = record.getRetryCount() + 1;
        String error = wireAck.getErrorCode() == null ? "业务处理失败" : wireAck.getErrorCode()
                + (wireAck.getErrorMessage() == null ? "" : ": " + wireAck.getErrorMessage());
        if (nextRetryCount >= config.getMaxDeliveryRetries()) {
            if (deliveryRepository.compareAndSetState(messageId, clientId, state,
                    MessageState.DEAD_LETTER, error, null, nextRetryCount)) {
                log.warn("失败次数耗尽，进入死信: messageId={}, clientId={}, retryCount={}",
                        messageId, clientId, nextRetryCount);
            } else {
                // 并发推进：幂等接受（重读确认不回退即可）
                log.debug("死信 CAS 竞争失败，按幂等处理: messageId={}, clientId={}", messageId, clientId);
            }
            return AckResult.ok();
        }
        long nextRetryAt = System.currentTimeMillis() + config.backoffDelayMs(nextRetryCount);
        // 接收 ACK 可能丢失：先前向补推 RECEIVED（失败忽略），再从最新合法状态进入 FAILED
        // （RECEIVED/ACCEPTED 不能直接跳 RETRYING；到期后由协调器推进 FAILED → RETRYING 再重投）
        MessageState source = state;
        if (source == MessageState.ACCEPTED || source == MessageState.CREATED) {
            deliveryRepository.compareAndSetState(messageId, clientId, source,
                    MessageState.RECEIVED, null, null, record.getRetryCount());
            source = deliveryRepository.find(messageId, clientId)
                    .orElse(record).getState();
        }
        if (source == MessageState.RECEIVED || source == MessageState.FAILED
                || source == MessageState.RETRYING) {
            if (deliveryRepository.compareAndSetState(messageId, clientId, source,
                    MessageState.FAILED, error, nextRetryAt, nextRetryCount)) {
                log.debug("业务处理失败，调度重试 #{}: messageId={}, clientId={}, nextRetryAt={}",
                        nextRetryCount, messageId, clientId, nextRetryAt);
            } else {
                // 并发推进：重读幂等判定
                DeliveryRecord latest = deliveryRepository.find(messageId, clientId).orElse(record);
                if (latest.getState() == MessageState.RETRYING || latest.getState() == MessageState.FAILED
                        || latest.getState() == MessageState.DEAD_LETTER) {
                    return AckResult.ok();
                }
                return AckResult.rejected(LinkoraErrorCode.INTERNAL_ERROR,
                        "当前状态 " + latest.getState() + " 不允许记录失败");
            }
        } else {
            return AckResult.rejected(LinkoraErrorCode.INTERNAL_ERROR,
                    "当前状态 " + source + " 不允许记录失败");
        }
        return AckResult.ok();
    }
}
