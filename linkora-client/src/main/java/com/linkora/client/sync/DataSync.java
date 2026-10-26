package com.linkora.client.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkora.client.UplinkPublisher;
import com.linkora.core.storage.client.ClientStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据同步门面（0.6.0）：面向"只读源库 + 定时上报"的旁挂数据同步客户端。
 *
 * <p>典型用法（业务系统不改造、旁挂客户端直读源库）：</p>
 * <pre>
 *   DataSync sync = DataSync.builder()
 *           .clientStore(clientStore)      // LinkoraClient 的存储（快照随客户端存储走）
 *           .objectMapper(new ObjectMapper())
 *           .chunkBytes(64 * 1024)
 *           .build();
 *
 *   List&lt;Map&lt;String, Object&gt;&gt; rows = 全量读取业务表();
 *   SyncDiff diff = sync.diff("tomb", rows, row -> (String) row.get("tomb_code"));
 *   if (!diff.isEmpty()) {
 *       sync.publish(uplink, "tomb-sync", diff);   // 组装标准信封并上行
 *   }
 *   sync.remember("tomb", rows, row -> (String) row.get("tomb_code")); // 发布成功后落快照
 * </pre>
 *
 * <p>消息形态（信封 messageType 由调用方给定，payload 结构如下）：</p>
 * <ul>
 *   <li>变更事件流（日常增量）：{@code {"syncKey","op":"ADD|UPDATE|DELETE","key","row"}}，
 *       sequenceKey = 业务主键（同键保序）；</li>
 *   <li>全量分片（变更集超过 chunkBytes）：{@code {"syncKey","chunk":{"seq","total","events"}}}，
 *       sequenceKey = chunk 序号（严格保序应用）；</li>
 *   <li>指纹对账（每日建议）：{@code fingerprint(retainFingerprint)} 由业务定时发送，
 *       线上比对不一致则触发一次全量。</li>
 * </ul>
 *
 * <p>线上侧按 key upsert（幂等），由业务 handler 实现（见示例 sync-client）。</p>
 *
 * @author lianghang
 */
public final class DataSync {

    /** 分片消息的 sequenceKey 前缀（严格保序）。 */
    private static final String CHUNK_SEQ_PREFIX = "__sync_chunk__";

    private final ClientStore clientStore;
    private final ObjectMapper objectMapper;
    private final int chunkBytes;

    private DataSync(Builder b) {
        this.clientStore = b.clientStore;
        this.objectMapper = b.objectMapper;
        this.chunkBytes = b.chunkBytes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 计算 diff（不落快照；发布成功后调 {@link #remember}）。 */
    public SyncDiff diff(String syncKey,
                         List<Map<String, Object>> currentRows,
                         SnapshotDiffer.RowKeyFn keyFn) {
        return SnapshotDiffer.diff(syncKey, clientStore.loadSyncSnapshot(syncKey),
                currentRows, keyFn);
    }

    /** 发布成功后调用：把当前全量行指纹落为快照（下次 diff 的基准）。 */
    public void remember(String syncKey,
                         List<Map<String, Object>> currentRows,
                         SnapshotDiffer.RowKeyFn keyFn) {
        SyncDiff diff = SnapshotDiffer.diff(syncKey, clientStore.loadSyncSnapshot(syncKey),
                currentRows, keyFn);
        Map<String, String> snapshot = new LinkedHashMap<String, String>();
        snapshot.put(SyncDiff.FINGERPRINT_KEY, diff.getFingerprint());
        for (Map<String, Object> row : currentRows) {
            String key = keyFn.key(row);
            snapshot.put(key, SnapshotDiffer.rowHash(row));
        }
        clientStore.saveSyncSnapshot(syncKey, snapshot);
    }

    /** 快捷路径：diff → 发布 → remember 一气呵成（发布异常时快照不更新，下次重发）。 */
    public SyncDiff diffAndPublish(UplinkPublisher uplink,
                                   String syncKey,
                                   String messageType,
                                   List<Map<String, Object>> currentRows,
                                   SnapshotDiffer.RowKeyFn keyFn) {
        SyncDiff diff = diff(syncKey, currentRows, keyFn);
        if (!diff.isEmpty()) {
            publish(uplink, messageType, diff);
        }
        remember(syncKey, currentRows, keyFn);
        return diff;
    }

    /**
     * 发布变更集：小变更走事件流（每行一条，sequenceKey=业务主键）；
     * 序列化后超过 chunkBytes 时走全量分片（sequenceKey=chunk 序号严格保序）。
     */
    public void publish(UplinkPublisher uplink, String messageType, SyncDiff diff) {
        if (uplink == null) {
            throw new IllegalArgumentException("uplink 不能为 null");
        }
        List<Map<String, Object>> events = buildEvents(diff);
        byte[] all = json(events);
        if (all.length <= chunkBytes) {
            for (Map<String, Object> event : events) {
                String key = String.valueOf(event.get("key"));
                uplink.publish(messageType, event, key);
            }
            return;
        }
        // 大变更集：按字节分片，chunk 序号严格保序
        List<List<Map<String, Object>>> chunks = splitByBytes(events);
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            Map<String, Object> chunk = new LinkedHashMap<String, Object>();
            chunk.put("syncKey", diff.getSyncKey());
            Map<String, Object> meta = new LinkedHashMap<String, Object>();
            meta.put("seq", i);
            meta.put("total", total);
            meta.put("events", chunks.get(i));
            chunk.put("chunk", meta);
            uplink.publish(messageType, chunk, CHUNK_SEQ_PREFIX + diff.getSyncKey() + "#" + i);
        }
    }

    /** 构造变更事件列表。 */
    private List<Map<String, Object>> buildEvents(SyncDiff diff) {
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : diff.getAdded()) {
            events.add(event(diff.getSyncKey(), "ADD", keyOf(diff, row), row));
        }
        for (Map<String, Object> row : diff.getUpdated()) {
            events.add(event(diff.getSyncKey(), "UPDATE", keyOf(diff, row), row));
        }
        for (String key : diff.getRemoved()) {
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("syncKey", diff.getSyncKey());
            e.put("op", "DELETE");
            e.put("key", key);
            events.add(e);
        }
        return events;
    }

    private String keyOf(SyncDiff diff, Map<String, Object> row) {
        // 事件流的 key 由行内提取：约定第一列为业务主键由调用方在 keyFn 中指定，
        // 这里按"行中存在 __key__ 覆盖"处理，否则取首列（保持 Map 顺序）
        if (row.containsKey("__key__")) {
            return String.valueOf(row.get("__key__"));
        }
        return String.valueOf(row.values().iterator().next());
    }

    private Map<String, Object> event(String syncKey, String op, String key, Map<String, Object> row) {
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("syncKey", syncKey);
        e.put("op", op);
        e.put("key", key);
        if (row != null) {
            e.put("row", row);
        }
        return e;
    }

    /** 按序列化字节大小切分事件列表（单事件超限独占一片）。 */
    private List<List<Map<String, Object>>> splitByBytes(List<Map<String, Object>> events) {
        List<List<Map<String, Object>>> chunks = new ArrayList<List<Map<String, Object>>>();
        List<Map<String, Object>> current = new ArrayList<Map<String, Object>>();
        int size = 0;
        for (Map<String, Object> event : events) {
            byte[] json = json(event);
            if (size + json.length > chunkBytes && !current.isEmpty()) {
                chunks.add(current);
                current = new ArrayList<Map<String, Object>>();
                size = 0;
            }
            current.add(event);
            size += json.length;
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private byte[] json(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException("事件序列化失败", e);
        }
    }

    /** Builder。 */
    public static final class Builder {
        private ClientStore clientStore;
        private ObjectMapper objectMapper = new ObjectMapper();
        private int chunkBytes = 64 * 1024;

        /** 宿主 LinkoraClient 的存储（快照随之落入 SQLite 文件或宿主库）。 */
        public Builder clientStore(ClientStore clientStore) {
            this.clientStore = clientStore;
            return this;
        }

        /** 自定义 ObjectMapper（可选，默认 new）。 */
        public Builder objectMapper(ObjectMapper objectMapper) {
            if (objectMapper != null) {
                this.objectMapper = objectMapper;
            }
            return this;
        }

        /** 分片阈值（字节），默认 64KB。 */
        public Builder chunkBytes(int chunkBytes) {
            if (chunkBytes <= 0) {
                throw new IllegalArgumentException("chunkBytes 必须 > 0");
            }
            this.chunkBytes = chunkBytes;
            return this;
        }

        public DataSync build() {
            if (clientStore == null) {
                throw new IllegalArgumentException("clientStore 不能为 null");
            }
            return new DataSync(this);
        }
    }
}
