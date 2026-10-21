package com.linkora.example.boot.service;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.server.pipeline.IngestListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 客户端上报处理服务：实现 {@link IngestListener}，客户端上行消息持久化成功后
 * 被框架回调——这里就是服务端"处理客户端消息"的地方。
 * 注册方式：本 bean 存在于容器中时，linkora-spring-boot-starter 自动接入。
 *
 * <p>回调在摄入线程上同步执行，示例仅做内存记录；真实业务请自行异步化。</p>
 *
 * @author lianghang
 */
@Service
public class ClientReportService implements IngestListener {

    private static final Logger log = LoggerFactory.getLogger(ClientReportService.class);

    /** 上行上报 topic（客户端 ReportService 以此类型发送）。 */
    public static final String TOPIC_REPORT = "demo-report";

    /** 内存保留的最大上报条数（演示用，真实场景应落库）。 */
    private static final int MAX_REPORTS = 100;

    private final Object lock = new Object();
    private final Deque<Report> reports = new ArrayDeque<Report>();

    @Override
    public void onIngested(LinkoraMessage message) {
        if (!TOPIC_REPORT.equals(message.getMessageType())) {
            return;
        }
        String text = new String(message.getPayload(), StandardCharsets.UTF_8);
        Report report = new Report(message.getMessageId(), message.getClientId(),
                text, message.getCreatedAt());
        synchronized (lock) {
            reports.addLast(report);
            while (reports.size() > MAX_REPORTS) {
                reports.removeFirst();
            }
        }
        log.info("收到客户端上报: clientId={}, text={}, messageId={}",
                message.getClientId(), text, message.getMessageId());
        System.out.println("[上报] clientId=" + message.getClientId()
                + " text=" + text + " messageId=" + message.getMessageId());
    }

    /** 已处理的上报列表（供 GET /api/reports 查询）。 */
    public List<Report> getReports() {
        synchronized (lock) {
            return new ArrayList<Report>(reports);
        }
    }

    /** 一条客户端上报（演示用简单值对象）。 */
    public static final class Report {
        private final String messageId;
        private final String clientId;
        private final String text;
        private final long sentAt;

        public Report(String messageId, String clientId, String text, long sentAt) {
            this.messageId = messageId;
            this.clientId = clientId;
            this.text = text;
            this.sentAt = sentAt;
        }

        public String getMessageId() {
            return messageId;
        }

        public String getClientId() {
            return clientId;
        }

        public String getText() {
            return text;
        }

        public long getSentAt() {
            return sentAt;
        }
    }
}
