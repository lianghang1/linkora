package com.linkora.client.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次 diff 的结果（0.6.0）。
 *
 * <p>{@code added} / {@code updated} 为当前行内容（Map 形态），{@code removed}
 * 只含业务主键（远端按 key 删除）。</p>
 *
 * @author lianghang
 */
public final class SyncDiff {

    /** 保留字：快照表中的整表指纹行。 */
    public static final String FINGERPRINT_KEY = "__fingerprint__";

    private final String syncKey;
    private final List<Map<String, Object>> added;
    private final List<Map<String, Object>> updated;
    private final List<String> removed;
    private final String fingerprint;

    SyncDiff(String syncKey,
             List<Map<String, Object>> added,
             List<Map<String, Object>> updated,
             List<String> removed,
             String fingerprint) {
        this.syncKey = syncKey;
        this.added = Collections.unmodifiableList(new ArrayList<Map<String, Object>>(added));
        this.updated = Collections.unmodifiableList(new ArrayList<Map<String, Object>>(updated));
        this.removed = Collections.unmodifiableList(new ArrayList<String>(removed));
        this.fingerprint = fingerprint;
    }

    public String getSyncKey() {
        return syncKey;
    }

    public List<Map<String, Object>> getAdded() {
        return added;
    }

    public List<Map<String, Object>> getUpdated() {
        return updated;
    }

    public List<String> getRemoved() {
        return removed;
    }

    /** 本次 diff 计算出的整表指纹（remember 后成为下次对比基准）。 */
    public String getFingerprint() {
        return fingerprint;
    }

    /** 无任何变更。 */
    public boolean isEmpty() {
        return added.isEmpty() && updated.isEmpty() && removed.isEmpty();
    }

    /** 变更总条数。 */
    public int size() {
        return added.size() + updated.size() + removed.size();
    }

    @Override
    public String toString() {
        return "SyncDiff{syncKey=" + syncKey + ", +" + added.size() + " ~" + updated.size()
                + " -" + removed.size() + "}";
    }
}
