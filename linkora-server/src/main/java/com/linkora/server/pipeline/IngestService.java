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

    public IngestResult ingest(AuthRequest request) {
        AuthResult auth = authenticator.authenticate(request);
        if (!auth.isAuthenticated()) {
            return IngestResult.rejected(
                    auth.getErrorCode() == null ? LinkoraErrorCode.AUTH_FAILED : auth.getErrorCode(),
                    auth.getMessage());
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
        } catch (LinkoraException e) {
            return IngestResult.rejected(LinkoraErrorCode.INVALID_MESSAGE, e.getMessage());
        }
        if (request.getClientId() == null
                || !request.getClientId().equals(message.getClientId())) {
            return IngestResult.rejected(LinkoraErrorCode.FORBIDDEN,
                    "消息 clientId 与认证身份不一致");
        }
        Optional<LinkoraMessage> existing = messageRepository.findByMessageId(message.getMessageId());
        if (existing.isPresent()) {
            log.debug("消息重复上报（幂等命中）: messageId={}, clientId={}",
                    message.getMessageId(), request.getClientId());
            return IngestResult.duplicate(message.getMessageId());
        }
        messageRepository.save(message);
        log.debug("消息已持久化接受: messageId={}, clientId={}, type={}",
                message.getMessageId(), message.getClientId(), message.getMessageType());
        notifyListeners(message);
        return IngestResult.accepted(message.getMessageId());
    }

    /** 业务侧监听回调（按注册顺序逐个调用）：异常只记告警，不影响上行结果。 */
    private void notifyListeners(LinkoraMessage message) {
        for (IngestListener listener : ingestListeners) {
            try {
                listener.onIngested(message);
            } catch (RuntimeException e) {
                log.warn("IngestListener 回调异常（不影响上行结果）: messageId={}",
                        message.getMessageId(), e);
            }
        }
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
