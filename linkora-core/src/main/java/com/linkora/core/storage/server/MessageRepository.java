package com.linkora.core.storage.server;

import com.linkora.core.message.LinkoraMessage;

import java.util.Optional;

/**
 * 服务端消息正文仓储。实现必须：
 * <ul>
 *   <li>messageId 唯一约束；重复保存幂等（不覆盖、不报错）。</li>
 *   <li>写入操作在数据库事务中完成，持久化成功才返回。</li>
 *   <li>线程安全。</li>
 * </ul>
 */
public interface MessageRepository {

    /** 幂等保存消息正文。 */
    void save(LinkoraMessage message);

    Optional<LinkoraMessage> findByMessageId(String messageId);
}
