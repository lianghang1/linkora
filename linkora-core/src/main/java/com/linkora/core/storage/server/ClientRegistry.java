package com.linkora.core.storage.server;

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
}
