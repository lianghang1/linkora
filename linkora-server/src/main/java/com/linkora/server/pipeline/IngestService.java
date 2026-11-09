package com.linkora.server.pipeline;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.serialize.jackson.MessageJsonCodec;
import com.linkora.server.ServerConfig;
import com.linkora.server.auth.AuthRequest;
import com.linkora.server.auth.AuthResult;
import com.linkora.server.auth.ClientAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 上行消息摄入服务：认证 → 协议校验 → 大小校验 → 结构解析 → 身份匹配 → 幂等持久化。
 * 全部通过后返回"服务端已持久化接受"；重复 messageId 幂等返回 DUPLICATE。
 *
 * @author lianghang
 */
public final class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ClientAuthenticator authenticator;
    private final MessageRepository messageRepository;
    private final MessageJsonCodec codec;
    private final ServerConfig config;
    private final List<IngestListener> ingestListeners;

    public IngestService(ClientAuthenticator authenticator,
                         MessageRepository messageRepository,
                         MessageJsonCodec codec,
                         ServerConfig config) {
        this(authenticator, messageRepository, codec, config,
                Collections.<IngestListener>emptyList());
    }

    /** 兼容旧单监听器用法；等价于只注册一个监听器的列表构造。 */
    public IngestService(ClientAuthenticator authenticator,
                         MessageRepository messageRepository,
                         MessageJsonCodec codec,
                         ServerConfig config,
                         IngestListener ingestListener) {
        this(authenticator, messageRepository, codec, config,
                ingestListener == null ? Collections.<IngestListener>emptyList()
                        : Collections.singletonList(ingestListener));
    }

    /** 多监听器构造：按列表顺序逐个回调。 */
    public IngestService(ClientAuthenticator authenticator,
                         MessageRepository messageRepository,
                         MessageJsonCodec codec,
                         ServerConfig config,
                         List<IngestListener> ingestListeners) {
        if (authenticator == null || messageRepository == null || codec == null || config == null) {
            throw new IllegalArgumentException("IngestService 依赖不能为 null");
        }
        this.authenticator = authenticator;
        this.messageRepository = messageRepository;
        this.codec = codec;
        this.config = config;
        this.ingestListeners = ingestListeners == null || ingestListeners.isEmpty()
                ? Collections.<IngestListener>emptyList()
                : Collections.unmodifiableList(new ArrayList<IngestListener>(ingestListeners));
    }

    /** lk_message.state 处理状态取值（与 v5 迁移列语义一致）。 */
    static final String STATE_ACCEPTED = "ACCEPTED";
    static final String STATE_PROCESSED = "PROCESSED";
    static final String STATE_FAILED = "FAILED";

    public IngestResult ingest(AuthRequest requestParam) {
        AuthRequest request = requestParam;
        AuthResult auth = authenticator.authenticate(request);
        if (!auth.isAuthenticated()) {
            return IngestResult.rejected(
                    auth.getErrorCode() == null ? LinkoraErrorCode.AUTH_FAILED : auth.getErrorCode(),
                    auth.getMessage());
        }
        // gzip：验签（覆盖压缩后字节）通过后解压，大小上限按解压后内容计；
        // header 用大小写不敏感读取（getHeaders() 是普通 Map，精确匹配会漏），
        // 并以 gzip 魔数兜底：中间层（网关/代理）可能改写或丢弃 Content-Encoding 头
        if ("gzip".equalsIgnoreCase(request.header("X-Linkora-Content-Encoding"))
                || com.linkora.core.util.Gzip.isGzipped(request.getBody())) {
            byte[] decompressed;
            try {
                decompressed = com.linkora.core.util.Gzip.decompress(request.getBody());
            } catch (java.io.IOException e) {
                return IngestResult.rejected(LinkoraErrorCode.INVALID_MESSAGE, "gzip 解压失败");
            }
            // 重建请求头：大小写不敏感地剔除传输编码头（header 名可能被中间层规范化）
            java.util.Map<String, String> hs =
                    new java.util.LinkedHashMap<String, String>();
            for (java.util.Map.Entry<String, String> e : request.getHeaders().entrySet()) {
                if (!"X-Linkora-Content-Encoding".equalsIgnoreCase(e.getKey())) {
                    hs.put(e.getKey(), e.getValue());
                }
            }
            request = new com.linkora.server.auth.AuthRequest(
                    request.getClientId(), request.getMethod(), hs, decompressed);
        }
        String protocolError = checkProtocol(request);
        if (protocolError != null) {
            return IngestResult.rejected(LinkoraErrorCode.UNSUPPORTED_PROTOCOL, protocolError);
        }
        if (request.getBodySize() > config.getMessageSizeLimitBytes()) {
            return IngestResult.rejected(LinkoraErrorCode.MESSAGE_TOO_LARGE,
                    "消息体超过大小上限 " + config.getMessageSizeLimitBytes() + " 字节");
        }
        LinkoraMessage message;
        try {
            message = codec.decodeMessage(new String(request.getBody(), StandardCharsets.UTF_8));
            if (message.getEnc() != null) {
                if (config.getPayloadCipherKey() == null) {
                    return IngestResult.rejected(LinkoraErrorCode.INVALID_MESSAGE,
                            "收到加密消息但服务端未配置 payload-cipher-key");
                }
                message = com.linkora.core.crypto.EncryptedMessages.decrypt(
                        com.linkora.core.crypto.PayloadCipher.decodeKey(
                                config.getPayloadCipherKey()), message);
            }
        } catch (LinkoraException e) {
            return IngestResult.rejected(LinkoraErrorCode.INVALID_MESSAGE, e.getMessage());
        }
        if (request.getClientId() == null
                || !request.getClientId().equals(message.getClientId())) {
            return IngestResult.rejected(LinkoraErrorCode.FORBIDDEN,
                    "消息 clientId 与认证身份不一致");
        }
        // 内部管理消息（linkora.admin.* 回传结果等）：一次性语义，不落库、不幂等检查，
        // 仅旁路回调（管理结果暂存收集器监听后读取）
        if (com.linkora.core.admin.ClientCommands.isInternalMessage(message.getMessageType())) {
            notifyListeners(message);
            return IngestResult.accepted(message.getMessageId());
        }
        Optional<LinkoraMessage> existing = messageRepository.findByMessageId(message.getMessageId());
        if (existing.isPresent()) {
            String state = messageRepository.processStateOf(message.getMessageId());
            // FAILED：此前业务处理失败，放行重新执行监听（显式重放/客户端死信重发触发）；
            // PROCESSED/ACCEPTED 及自实现仓储查不到状态（null）一律按重复响应，
            // 防止已成功消息被重放、处理中消息被并发双跑
            if (STATE_FAILED.equals(state)) {
                log.info("处理失败消息重放: messageId={}, clientId={}",
                        message.getMessageId(), request.getClientId());
                return replayListeners(message);
            }
            log.debug("消息重复上报（幂等命中）: messageId={}, clientId={}, state={}",
                    message.getMessageId(), request.getClientId(), state);
            return IngestResult.duplicate(message.getMessageId());
        }
        boolean inserted = messageRepository.save(message);
        if (!inserted) {
            // 并发上报同一 messageId：先到者已持久化并回调业务监听器，
            // 后到者必须止步于此，否则同一消息的业务 handler 会被执行两次
            log.debug("并发重复上报（幂等命中）: messageId={}, clientId={}",
                    message.getMessageId(), request.getClientId());
            return IngestResult.duplicate(message.getMessageId());
        }
        log.debug("消息已持久化接受: messageId={}, clientId={}, type={}",
                message.getMessageId(), message.getClientId(), message.getMessageType());
        String listenerError = notifyListeners(message);
        if (listenerError != null) {
            messageRepository.updateProcessState(message.getMessageId(), STATE_FAILED);
            log.warn("上行消息业务处理失败: messageId={}, clientId={}, 错误: {}",
                    message.getMessageId(), message.getClientId(), listenerError);
            return IngestResult.processingFailed(message.getMessageId(), listenerError);
        }
        messageRepository.updateProcessState(message.getMessageId(), STATE_PROCESSED);
        return IngestResult.accepted(message.getMessageId());
    }

    /** 失败消息重放（消息已入库，无需再 save）：结果与首次处理一致地回写状态。 */
    private IngestResult replayListeners(LinkoraMessage message) {
        String listenerError = notifyListeners(message);
        if (listenerError != null) {
            messageRepository.updateProcessState(message.getMessageId(), STATE_FAILED);
            return IngestResult.processingFailed(message.getMessageId(), listenerError);
        }
        messageRepository.updateProcessState(message.getMessageId(), STATE_PROCESSED);
        return IngestResult.accepted(message.getMessageId());
    }

    /**
     * 业务侧监听回调（按注册顺序逐个调用）：异常记录告警并收集首个错误信息
     * （驱动 state=FAILED 与 422 响应）；全部成功返回 null。
     * 内部管理消息的旁路回调异常不影响上行结果（调用方忽略返回值）。
     */
    private String notifyListeners(LinkoraMessage message) {
        String firstError = null;
        for (IngestListener listener : ingestListeners) {
            try {
                listener.onIngested(message);
            } catch (RuntimeException e) {
                log.warn("IngestListener 回调异常: messageId={}", message.getMessageId(), e);
                if (firstError == null) {
                    firstError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
        }
        return firstError;
    }

    /** 协议版本头校验；返回 null 表示通过。 */
    static String checkProtocol(AuthRequest request) {
        String version = request.header(com.linkora.core.protocol.Protocol.PROTOCOL_VERSION_HEADER);
        if (version == null || version.trim().isEmpty()) {
            return "缺少协议版本头 " + com.linkora.core.protocol.Protocol.PROTOCOL_VERSION_HEADER;
        }
        if (!String.valueOf(com.linkora.core.protocol.Protocol.PROTOCOL_VERSION).equals(version.trim())) {
            return "不支持的协议版本: " + version;
        }
        return null;
    }
}
