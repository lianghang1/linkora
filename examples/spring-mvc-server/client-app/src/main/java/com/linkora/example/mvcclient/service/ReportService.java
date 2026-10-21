package com.linkora.example.mvcclient.service;

import com.linkora.client.LinkoraClient;
import com.linkora.core.message.OutboxStatus;
import com.linkora.core.storage.client.ClientStore;

import java.nio.charset.StandardCharsets;

/**
 * 上行上报服务：把业务数据交给 {@link LinkoraClient} 发送。
 * publish 先写 SQLite outbox（进程崩溃不丢），后台线程自动 POST 服务端，
 * 再用 {@link #awaitServerAccepted} 轮询本地队列确认服务端已接受。
 *
 * @author lianghang
 */
public class ReportService {

    /** 上行上报 topic（服务端 ClientReportService 监听该类型）。 */
    public static final String TOPIC_REPORT = "demo-report";

    private final LinkoraClient client;
    private final ClientStore store;

    public ReportService(LinkoraClient client, ClientStore store) {
        this.client = client;
        this.store = store;
    }

    /** 发送一条文本上报，返回消息 ID（此时可能尚未到达服务端）。 */
    public String submit(String text) {
        return client.publish(TOPIC_REPORT, text.getBytes(StandardCharsets.UTF_8));
    }

    /** 轮询本地队列，等待该消息被服务端确认（outbox 状态 SERVER_ACCEPTED）。 */
    public boolean awaitServerAccepted(String messageId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (store.findOutbox(messageId)
                    .filter(entry -> entry.getStatus() == OutboxStatus.SERVER_ACCEPTED)
                    .isPresent()) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }
}
