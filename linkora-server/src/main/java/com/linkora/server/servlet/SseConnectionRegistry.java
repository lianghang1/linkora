package com.linkora.server.servlet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 按 clientId 组织的 SSE 连接注册表。线程安全。
 * 同一 clientId 允许存在多条并发连接（如客户端重连竞态窗口），
 * 推送会向该 clientId 的所有活动连接尽力写出。
 *
 * @author lianghang
 */
public final class SseConnectionRegistry {

    private final ConcurrentHashMap<String, List<SseClientConnection>> connections =
            new ConcurrentHashMap<String, List<SseClientConnection>>();

    public void register(SseClientConnection connection) {
        List<SseClientConnection> list = new CopyOnWriteArrayList<SseClientConnection>();
        List<SseClientConnection> existing = connections.putIfAbsent(
                connection.getClientId(), list);
        (existing == null ? list : existing).add(connection);
    }

    public void unregister(SseClientConnection connection) {
        List<SseClientConnection> list = connections.get(connection.getClientId());
        if (list != null) {
            list.remove(connection);
            if (list.isEmpty()) {
                connections.remove(connection.getClientId(), list);
            }
        }
    }

    /** 指定客户端的活动连接快照。 */
    public List<SseClientConnection> getConnections(String clientId) {
        List<SseClientConnection> list = connections.get(clientId);
        return list == null
                ? Collections.<SseClientConnection>emptyList()
                : new ArrayList<SseClientConnection>(list);
    }

    /**
     * 向指定客户端的所有活动连接推送事件。
     *
     * @return 至少一条连接成功写出的数量（0 表示客户端当前不在线）
     */
    public int broadcast(String clientId, String id, String event, String data) {
        int delivered = 0;
        for (SseClientConnection connection : getConnections(clientId)) {
            if (connection.sendEvent(id, event, data)) {
                delivered++;
            }
        }
        return delivered;
    }

    /** 当前有活动连接的客户端 ID 快照。 */
    public Set<String> activeClientIds() {
        return Collections.unmodifiableSet(connections.keySet());
    }

    public int totalConnections() {
        int total = 0;
        for (List<SseClientConnection> list : connections.values()) {
            total += list.size();
        }
        return total;
    }

    /** 关闭全部连接（服务端优雅停机时使用）。 */
    public void closeAll() {
        for (List<SseClientConnection> list : connections.values()) {
            for (SseClientConnection connection : list) {
                connection.close();
            }
        }
        connections.clear();
    }
}
