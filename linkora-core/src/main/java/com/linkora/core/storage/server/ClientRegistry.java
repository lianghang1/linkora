package com.linkora.core.storage.server;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 客户端注册与连接状态仓储。实现必须线程安全，注册幂等。
 *
 * @author lianghang
 */
public interface ClientRegistry {

    void register(ClientRegistration registration);

    Optional<ClientRegistration> findByClientId(String clientId);

    void updateConnectionState(String clientId, ConnectionState state, long lastActiveAt);

    /**
     * 全部已注册客户端（管理/观测接口使用）。
     * 默认返回空列表：自实现仓储不覆盖不会破坏编译。
     */
    default List<ClientRegistration> findAll() {
        return Collections.emptyList();
    }

    /**
     * 管理接口：运行时启用/禁用客户端（禁用后拒收新消息、拒绝接入）。
     *
     * @return true 表示更新成功；false 表示客户端不存在
     */
    default boolean setEnabled(String clientId, boolean enabled) {
        return false;
    }

    /**
     * 管理接口：从白名单注销客户端（删除注册记录；历史消息与投递数据保留）。
     * 供运维控制台的「注销客户端」操作使用；注销前应先断开其连接。
     * 默认返回 false（不支持注销），自实现仓储不覆盖不会破坏编译。
     *
     * @return true 表示注销成功；false 表示客户端不存在或不支持注销
     */
    default boolean removeClient(String clientId) {
        return false;
    }
}
