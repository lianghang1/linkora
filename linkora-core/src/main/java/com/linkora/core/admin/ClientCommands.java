package com.linkora.core.admin;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 服务端 → 客户端远程管理指令的白名单。
 *
 * <p>指令通过 SSE {@code ctrl} 事件实时下发（不落库、不混业务消息）：
 * 仅对在线客户端生效，未送达即返回失败，由运维再次触发。
 * 指令执行端必须按本白名单分发，未知指令忽略并告警——
 * 管理通道不是通用执行通道，每条指令显式定义。</p>
 *
 * @author lianghang
 */
public final class ClientCommands {

    /** SSE 控制事件类型（与业务消息 "msg"、心跳 "hb" 区分）。 */
    public static final String EVENT_TYPE = "ctrl";

    /** 重发指定上行死信。参数：{@code messageId}。 */
    public static final String RETRY_OUTBOX_DEAD_LETTER = "RETRY_OUTBOX_DEAD_LETTER";

    /** 放弃指定上行死信（留痕）。参数：{@code messageId}、{@code reason}。 */
    public static final String DISCARD_OUTBOX_DEAD_LETTER = "DISCARD_OUTBOX_DEAD_LETTER";

    /** 连通性探测（无参数）。 */
    public static final String PING = "PING";

    /**
     * 远程查询客户端本地 outbox（结果经上行回传）。参数：
     * {@code queryId}、{@code status}（OutboxStatus 名，可空=全部）、{@code limit}（可空，默认 50）。
     */
    public static final String QUERY_OUTBOX = "QUERY_OUTBOX";

    /** 指令执行结果回传使用的上行消息类型（服务端据此识别并暂存结果）。 */
    public static final String RESULT_MESSAGE_TYPE = "linkora.admin.result";

    private static final Set<String> WHITELIST = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    RETRY_OUTBOX_DEAD_LETTER, DISCARD_OUTBOX_DEAD_LETTER,
                    PING, QUERY_OUTBOX)));

    /** 是否在白名单内。 */
    public static boolean isKnown(String command) {
        return command != null && WHITELIST.contains(command);
    }

    private ClientCommands() {
    }
}
