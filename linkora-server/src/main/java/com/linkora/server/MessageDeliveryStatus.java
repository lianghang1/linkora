package com.linkora.server;

import com.linkora.core.message.LinkoraMessage;
import com.linkora.core.storage.server.DeliveryRecord;

import java.util.Collections;
import java.util.List;

/** 消息状态查询结果：消息正文元信息 + 各目标客户端的投递状态。
 *
 * @author lianghang
 */
public final class MessageDeliveryStatus {

    private final LinkoraMessage message;
    private final List<DeliveryRecord> deliveryRecords;

    public MessageDeliveryStatus(LinkoraMessage message, List<DeliveryRecord> deliveryRecords) {
        if (message == null) {
            throw new IllegalArgumentException("message 不能为 null");
        }
        this.message = message;
        this.deliveryRecords = deliveryRecords == null
                ? Collections.<DeliveryRecord>emptyList()
                : Collections.unmodifiableList(deliveryRecords);
    }

    public LinkoraMessage getMessage() {
        return message;
    }

    public List<DeliveryRecord> getDeliveryRecords() {
        return deliveryRecords;
    }

    /** 是否所有投递都到达终态（含过期/死信）。 */
    public boolean isSettled() {
        for (DeliveryRecord record : deliveryRecords) {
            if (!record.getState().isTerminal()) {
                return false;
            }
        }
        return true;
    }
}
