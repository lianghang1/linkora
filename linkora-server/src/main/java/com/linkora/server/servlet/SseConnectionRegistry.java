package com.linkora.server.servlet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 clientId 组织的 SSE 连接注册表。线程安全。
 * 同一 clientId 允许存在多条并发连接（如客户端重连竞态窗口），
 * 推送会向该 clientId 的所有活动连接尽力写出。
 *
 * @author lianghang
 */
public final class SseConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);

    private final ConcurrentHashMap<String, List<SseClientConnection>> connections =
            new ConcurrentHashMap<String, List<SseClientConnection>>();

    public void register(SseClientConnection connection) {
        List<SseClientConnection> list = new CopyOnWriteArrayList<SseClientConnection>();
        List<SseClientConnection> existing = connections.putIfAbsent(
                connection.getClientId(), list);
        List<SseClientConnection> target = existing == null ? list : existing;
        target.add(connection);
        int size = target.size();
        if (size > 1) {
            // 正常仅为重连竞态窗口（旧连接即将 unregister）；若持续出现，通常是同一 clientId
            // 的配置/token 被复制到了多台设备——多台会同时消费下行并各自 ACK，产生业务级重复处理
            log.warn("同一 clientId 存在 {} 条并发 SSE 连接: clientId={}（短时出现属重连竞态，"
                    + "持续出现需排查配置是否被复制到多台设备）", size, connection.getClientId());
        }
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
            if (!connection.isReadyForPush()) {
                // 未通过认证的连接不接收业务数据（认证失败即将被注销）
                continue;
            }
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

    /** 全部连接快照（写超时巡检、监控用）。 */
    public List<SseClientConnection> allConnections() {
        List<SseClientConnection> all = new ArrayList<SseClientConnection>();
        for (List<SseClientConnection> list : connections.values()) {
            all.addAll(list);
        }
        return all;
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
