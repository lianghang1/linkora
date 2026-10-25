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
 *
 * @author lianghang
 */
public interface MessageRepository {

    /**
     * 幂等保存消息正文。主键插入是并发去重的唯一仲裁点：
     * 调用方依赖返回值区分"新消息"与"并发重复"，只有新插入才允许触发业务回调。
     *
     * @return true 表示新插入；false 表示 messageId 已存在（保留原记录）
     */
    boolean save(LinkoraMessage message);

    Optional<LinkoraMessage> findByMessageId(String messageId);

    // ---------------- 管理/观测查询（可选能力） ----------------

    /**
     * 管理接口：按条件分页查询消息正文，按 created_at 倒序。
     * 全部过滤条件可为 null（不过滤）。
     *
     * <p>default 返回空列表：自实现仓储不覆盖不会破坏编译。
     * 无 clientId 的全局时间查询在大表上可能全表扫描，管理端建议带上 clientId。</p>
     *
     * @param clientId    目标客户端，null 不过滤
     * @param messageType 消息类型，null 不过滤
     * @param beginTime   发布时间下界（epoch 毫秒，含），null 不过滤
     * @param endTime     发布时间上界（epoch 毫秒，含），null 不过滤
     * @param offset      跳过条数（>=0）
     * @param limit       返回条数（1~500）
     */
    default java.util.List<LinkoraMessage> listMessages(String clientId, String messageType,
                                                        Long beginTime, Long endTime,
                                                        int offset, int limit) {
        return listMessages(clientId, messageType, null, beginTime, endTime, offset, limit);
    }

    /**
     * 管理接口：带关键词的管理查询（匹配 subject / messageId / messageType）。
     * default 忽略关键词并委托给不带关键词的版本：自实现仓储不覆盖不会破坏编译。
     *
     * @param keyword 关键词，null/空白不过滤
     */
    default java.util.List<LinkoraMessage> listMessages(String clientId, String messageType,
                                                        String keyword,
                                                        Long beginTime, Long endTime,
                                                        int offset, int limit) {
        return java.util.Collections.emptyList();
    }

    /**
     * 管理接口：最近 {@code days} 天内每日发布量（key = 本地日期 yyyy-MM-dd，升序）。
     * default 返回空 Map。
     */
    default java.util.LinkedHashMap<String, Long> countPublishedPerDay(int days) {
        return new java.util.LinkedHashMap<>();
    }
}
