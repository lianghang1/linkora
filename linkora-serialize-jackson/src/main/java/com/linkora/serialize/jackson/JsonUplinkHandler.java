package com.linkora.serialize.jackson;

import com.fasterxml.jackson.core.type.TypeReference;
import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.uplink.UplinkHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * {@link UplinkHandler} 的 JSON 便捷基类：自动将上行消息 payload（JSON/UTF-8）
 * 反序列化为业务 DTO，子类只需实现 {@link #handle(LinkoraMessage, Object)}
 * 处理强类型对象，无需手写解析与异常分支。
 *
 * <pre>
 * public class OrderStatusHandler extends JsonUplinkHandler&lt;OrderStatusHandler.Payload&gt; {
 *     public OrderStatusHandler() { super(Payload.class); }                          // 单个对象
 *     public String messageType() { return "order-status-updated"; }
 *     protected void handle(LinkoraMessage message, Payload payload) { ... }
 * }
 * public class BatchHandler extends JsonUplinkHandler&lt;List&lt;Payload&gt;&gt; {
 *     public BatchHandler() { super(new TypeReference&lt;List&lt;Payload&gt;&gt;() { }); }    // 集合/泛型
 *     ...
 * }
 * </pre>
 *
 * <p>DTO 要求：公共字段或 getter/setter（Jackson 默认可见性）。解析失败/空白
 * payload 默认记 WARN 后忽略（不中断摄入流程，消息已持久化不丢失），需要定制
 * （告警、死信落库等）可覆写 {@link #onParseError}。实例线程安全。</p>
 *
 * @author lianghang
 */
public abstract class JsonUplinkHandler<T> implements UplinkHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonUplinkHandler.class);

    private final Class<T> payloadClass;
    private final TypeReference<T> payloadTypeRef;

    /** 单个对象类型：{@code super(DemoPayload.class)}。 */
    protected JsonUplinkHandler(Class<T> payloadType) {
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType 不能为 null");
        }
        this.payloadClass = payloadType;
        this.payloadTypeRef = null;
    }

    /**
     * 集合/泛型类型：{@code super(new TypeReference<List<DemoPayload>>() { })}。
     * 不要用 {@code List.class}——泛型擦除会让元素反序列化成 LinkedHashMap。
     */
    protected JsonUplinkHandler(TypeReference<T> payloadType) {
        if (payloadType == null) {
            throw new IllegalArgumentException("payloadType 不能为 null");
        }
        this.payloadClass = null;
        this.payloadTypeRef = payloadType;
    }

    /**
     * payload 反序列化目标类型；TypeReference 构造时返回 null（泛型参数
     * 保留在 {@code payloadTypeRef} 中，无法还原为 Class）。
     */
    public final Class<T> payloadType() {
        return payloadClass;
    }

    @Override
    public final void handle(LinkoraMessage message) {
        byte[] raw = message.getPayload();
        T payload;
        try {
            payload = raw == null || raw.length == 0
                    ? null
                    : deserialize(new String(raw, StandardCharsets.UTF_8));
        } catch (LinkoraException e) {
            onParseError(message, e);
            return;
        }
        if (payload == null) {
            onParseError(message, new LinkoraException(LinkoraErrorCode.SERIALIZATION_ERROR,
                    "payload 为空或空白内容"));
            return;
        }
        handle(message, payload);
    }

    private T deserialize(String json) {
        return payloadClass != null
                ? LinkoraJsonUtil.fromJson(json, payloadClass)
                : LinkoraJsonUtil.fromJson(json, payloadTypeRef);
    }

    /** 子类实现：payload 已反序列化为业务 DTO，只写纯业务逻辑。 */
    protected abstract void handle(LinkoraMessage message, T payload);

    /**
     * payload 反序列化失败（含空 payload）时的兜底：默认记 WARN 后忽略。
     * 消息在服务端已持久化，不因业务解析失败丢失；可覆写实现告警/死信等定制。
     */
    protected void onParseError(LinkoraMessage message, LinkoraException cause) {
        log.warn("上行消息 payload 反序列化失败，已忽略: type={}, messageId={}",
                messageType(), message.getMessageId(), cause);
    }
}
