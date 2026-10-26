package com.linkora.client.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 快照 diff 纯函数（0.6.0）：全量读源 + 上次快照指纹 → 增量。
 *
 * <p>行指纹 = 对行的排序键值对拼接后取 SHA-256 前 32 hex 字符。删除识别来自
 * "快照有、本次全量没有"，这是纯客户端 diff 相比 CDC 的天然优势。</p>
 *
 * @author lianghang
 */
public final class SnapshotDiffer {

    private SnapshotDiffer() {
    }

    /** 行主键提取器。 */
    public interface RowKeyFn extends java.io.Serializable {
        String key(Map<String, Object> row);
    }

    /**
     * 计算 diff。不落任何状态——调用方在发布成功后调用
     * {@code DataSync.remember} 落快照（避免"发布失败但快照已更新"导致丢变更）。
     *
     * @param syncKey    同步任务标识（一个业务表一个）
     * @param lastSnapshot 上次保存的快照（key → rowHash，可含 __fingerprint__ 保留行）
     * @param currentRows  本次全量读取的行
     * @param keyFn        业务主键提取
     */
    public static SyncDiff diff(String syncKey,
                                Map<String, String> lastSnapshot,
                                List<Map<String, Object>> currentRows,
                                RowKeyFn keyFn) {
        if (syncKey == null || syncKey.trim().isEmpty()) {
            throw new IllegalArgumentException("syncKey 不能为空");
        }
        if (keyFn == null) {
            throw new IllegalArgumentException("keyFn 不能为空");
        }
        Map<String, String> last = new LinkedHashMap<String, String>();
        if (lastSnapshot != null) {
            for (Map.Entry<String, String> e : lastSnapshot.entrySet()) {
                if (!SyncDiff.FINGERPRINT_KEY.equals(e.getKey())) {
                    last.put(e.getKey(), e.getValue());
                }
            }
        }

        List<Map<String, Object>> added = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> updated = new ArrayList<Map<String, Object>>();
        List<String> removed = new ArrayList<String>();
        Map<String, String> currentHashes = new LinkedHashMap<String, String>();

        for (Map<String, Object> row : currentRows) {
            if (row == null) {
                continue;
            }
            String key = keyFn.key(row);
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("行主键不能为空（keyFn 返回了空值）");
            }
            String hash = rowHash(row);
            currentHashes.put(key, hash);
            String lastHash = last.get(key);
            if (lastHash == null) {
                added.add(row);
            } else if (!lastHash.equals(hash)) {
                updated.add(row);
            }
        }
        for (String key : last.keySet()) {
            if (!currentHashes.containsKey(key)) {
                removed.add(key);
            }
        }

        StringBuilder fp = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<String, String>(currentHashes).entrySet()) {
            fp.append(e.getKey()).append(':').append(e.getValue()).append(';');
        }
        String fingerprint = sha256Hex(fp.toString());

        return new SyncDiff(syncKey, added, updated, removed, fingerprint);
    }

    /** 行指纹：排序键值对拼接后 SHA-256 前 32 hex 字符。 */
    public static String rowHash(Map<String, Object> row) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : new TreeMap<String, Object>(row).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sha256Hex(sb.toString()).substring(0, 32);
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
