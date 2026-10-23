package com.linkora.server.admin;

/**
 * 管理/观测视图的时间与 payload 格式化：输出"人话"，
 * 时间为 {@code yyyy-MM-dd HH:mm:ss}，payload 为 UTF-8 文本预览（绝不输出 Base64）。
 *
 * @author lianghang
 */
final class AdminViews {

    /** payload 预览的最大字符数。 */
    static final int PREVIEW_MAX_CHARS = 200;

    private AdminViews() {
    }

    /** epoch 毫秒 → {@code yyyy-MM-dd HH:mm:ss}；null 返回 "—"。 */
    static String formatTime(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return "—";
        }
        return java.time.Instant.ofEpochMilli(epochMillis)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * payload 的人类可读预览：UTF-8 解码后截断；
     * 含不可打印字节（非文本内容）时只标注字节数，不输出乱码。
     */
    static String payloadPreview(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return "（空）";
        }
        String text = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        if (!isPrintableText(text)) {
            return "[二进制数据 " + payload.length + " 字节]";
        }
        if (text.length() > PREVIEW_MAX_CHARS) {
            return text.substring(0, PREVIEW_MAX_CHARS) + "…（共 " + text.length() + " 字符）";
        }
        return text;
    }

    /** 可打印判定：允许常见空白（空格/换行/制表），其余控制字符视为二进制。 */
    private static boolean isPrintableText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** 投递状态的中文描述。 */
    static String stateText(com.linkora.core.message.MessageState state) {
        switch (state) {
            case CREATED:    return "已创建";
            case ACCEPTED:   return "已接受，等待推送";
            case RECEIVED:   return "客户端已接收";
            case PROCESSED:  return "处理成功（终态）";
            case FAILED:     return "处理失败，待重试";
            case RETRYING:   return "重试中";
            case EXPIRED:    return "已过期/已跳过（终态）";
            case DEAD_LETTER: return "死信，等待人工介入";
            default:         return state.name();
        }
    }
}
