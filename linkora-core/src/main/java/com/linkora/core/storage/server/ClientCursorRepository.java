package com.linkora.core.storage.server;

import java.util.OptionalLong;

/**
 * 客户端投递游标仓储。游标是辅助恢复手段，
 * 补发的最终依据仍是持久化投递记录与 ACK 状态。
 */
public interface ClientCursorRepository {

    /** 保存客户端最后已投递 seq。实现必须保证单调前进（不允许回退）。 */
    void saveLastDeliveredSeq(String clientId, long seq);

    /** 无记录时返回空 OptionalLong。 */
    OptionalLong getLastDeliveredSeq(String clientId);
}
