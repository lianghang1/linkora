package com.linkora.server.pipeline;

import com.linkora.server.servlet.SseClientConnection;
import com.linkora.server.servlet.SseConnectionRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** 基于 {@link SseConnectionRegistry} 的默认推送网关。
 *
 * @author lianghang
 */
public final class RegistryPushGateway implements SsePushGateway {

    private final SseConnectionRegistry registry;

    public RegistryPushGateway(SseConnectionRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry 不能为 null");
        }
        this.registry = registry;
    }

    @Override
    public int push(String clientId, String eventId, String eventType, String data) {
        return registry.broadcast(clientId, eventId, eventType, data);
    }

    @Override
    public boolean isConnected(String clientId) {
        return !registry.getConnections(clientId).isEmpty();
    }

    @Override
    public int connectionCount(String clientId) {
        return registry.getConnections(clientId).size();
    }

    @Override
    public Set<String> activeClientIds() {
        return registry.activeClientIds();
    }

    @Override
    public void closeAll() {
        registry.closeAll();
    }

    /** 暴露底层注册表（宿主需要用它构造 SsePushServlet）。 */
    public SseConnectionRegistry registry() {
        return registry;
    }

    /** 指定客户端的活动连接快照（连接状态判断用）。 */
    public List<SseClientConnection> connectionsOf(String clientId) {
        List<SseClientConnection> list = registry.getConnections(clientId);
        return list == null ? Collections.<SseClientConnection>emptyList() : new ArrayList<SseClientConnection>(list);
    }
}
