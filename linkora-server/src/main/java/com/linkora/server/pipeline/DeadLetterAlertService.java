package com.linkora.server.pipeline;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.storage.server.DeliveryRecord;
import com.linkora.core.storage.server.DeliveryRepository;
import com.linkora.core.storage.server.MessageRepository;
import com.linkora.server.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


/**
 * 死信主动告警（可配置，默认关闭）。
 *
 * <p>设计原则：发现靠推送，不靠打开。每 15 秒增量扫描新增死信
 * （updated_at &gt; 上次扫描水位），对每条新死信向配置的 Webhook 发送一条
 * 人类可读告警。启动时水位取当前时间——重启不会重放历史死信。</p>
 *
 * <p>发送异步且失败只记日志（告警是尽力而为的旁路，绝不影响投递主链路）。
 * 报文格式支持 raw / wecom（企业微信群机器人）/ dingtalk（钉钉群机器人）/
 * feishu（飞书群机器人），后三者包一层群机器人协议信封。</p>
 */
public final class DeadLetterAlertService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAlertService.class);

    private final DeliveryRepository deliveryRepository;
    private final MessageRepository messageRepository;
    private final ServerConfig config;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private volatile long lastSeenUpdatedAt;

    public DeadLetterAlertService(DeliveryRepository deliveryRepository,
                                  MessageRepository messageRepository, ServerConfig config) {
        this.deliveryRepository = deliveryRepository;
        this.messageRepository = messageRepository;
        this.config = config;
    }

    /** 启动增量扫描（由 LinkoraServer.start 调用；未配置 Webhook 时不启动）。 */
    public synchronized void start() {
        if (config.getAlertWebhookUrl() == null || task != null) {
            return;
        }
        // 启动水位 = 当前时间：只告警启动之后新产生的死信
        lastSeenUpdatedAt = System.currentTimeMillis();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "linkora-deadletter-alert");
            t.setDaemon(true);
            return t;
        });
        task = scheduler.scheduleWithFixedDelay(this::scanOnce, 15, 15, TimeUnit.SECONDS);
        log.info("死信告警已启用: type={}, webhook=<已配置>", config.getAlertWebhookType());
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void scanOnce() {
        try {
            List<DeliveryRecord> fresh =
                    deliveryRepository.findDeadLettersSince(lastSeenUpdatedAt, 20);
            for (DeliveryRecord record : fresh) {
                if (record.getUpdatedAt() > lastSeenUpdatedAt) {
                    lastSeenUpdatedAt = record.getUpdatedAt();
                }
                sendAsync(record);
            }
        } catch (Exception e) {
            log.warn("死信告警扫描失败（下轮重试）: {}", e.getMessage());
        }
    }

    private void sendAsync(DeliveryRecord record) {
        Thread sender = new Thread(() -> {
            try {
                send(record);
            } catch (Exception e) {
                log.warn("死信告警发送失败（不影响投递）: messageId={}, {}",
                        record.getMessageId(), e.getMessage());
            }
        }, "linkora-alert-send");
        sender.setDaemon(true);
        sender.start();
    }

    private void send(DeliveryRecord record) throws Exception {
        String subject = messageRepository.findByMessageId(record.getMessageId())
                .map(LinkoraMessage::getSubject).orElse(null);
        String text = buildText(record, subject);
        String type = config.getAlertWebhookType();
        String body;
        if ("wecom".equals(type) || "dingtalk".equals(type)) {
            body = "{\"msgtype\":\"text\",\"text\":{\"content\":"
                    + jsonString(text) + "}}";
        } else if ("feishu".equals(type)) {
            body = "{\"msg_type\":\"text\",\"content\":{\"text\":"
                    + jsonString(text) + "}}";
        } else {
            body = "{\"event\":\"dead_letter\",\"text\":" + jsonString(text)
                    + ",\"clientId\":" + jsonString(record.getClientId())
                    + ",\"messageId\":" + jsonString(record.getMessageId())
                    + ",\"reason\":" + jsonString(record.getLastError() == null ? "" : record.getLastError())
                    + "}";
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(config.getAlertWebhookUrl())
                .openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code / 100 != 2) {
                log.warn("死信告警 Webhook 返回非 2xx: {}", code);
            } else {
                log.info("死信告警已推送: messageId={}, clientId={}",
                        record.getMessageId(), record.getClientId());
            }
        } finally {
            conn.disconnect();
        }
    }

    private String buildText(DeliveryRecord record, String subject) {
        String title = subject != null ? subject : record.getMessageId();
        return "【Linkora 死信告警】\n主题：" + title
                + "\n客户端：" + record.getClientId()
                + "\n原因：" + (record.getLastError() == null ? "—" : record.getLastError())
                + "\n重试次数：" + record.getRetryCount()
                + "\n请打开控制台查看轨迹并处置。";
    }

    /** 最小 JSON 字符串转义（控制字符/引号/反斜杠）。 */
    private static String jsonString(String raw) {
        if (raw == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
