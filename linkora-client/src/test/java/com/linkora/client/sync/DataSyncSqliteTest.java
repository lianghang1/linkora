package com.linkora.client.sync;

import com.linkora.client.storage.sqlite.SqliteClientStore;
import com.linkora.core.storage.client.ClientStore;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 数据同步工具冒烟：快照存取（SQLite 落盘）+ diff 三态语义 + 指纹稳定性。
 *
 * @author lianghang
 */
public class DataSyncSqliteTest {

    private ClientStore newStore() throws Exception {
        Path dir = Files.createTempDirectory("linkora-sync-test");
        java.io.File f = new java.io.File(dir.toFile(), "db.sqlite");
        f.deleteOnExit();
        dir.toFile().deleteOnExit();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        return new SqliteClientStore(conn);
    }

    private Map<String, Object> row(String code, String name, int area) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("tomb_code", code);
        row.put("tomb_name", name);
        row.put("area", area);
        return row;
    }

    private List<Map<String, Object>> rows(Map<String, Object>... items) {
        return new ArrayList<Map<String, Object>>(Arrays.asList(items));
    }

    private SnapshotDiffer.RowKeyFn keyFn() {
        return new SnapshotDiffer.RowKeyFn() {
            @Override
            public String key(Map<String, Object> row) {
                return String.valueOf(row.get("tomb_code"));
            }
        };
    }

    @Test
    public void firstDiffTreatsAllRowsAsAdded() throws Exception {
        DataSync sync = DataSync.builder().clientStore(newStore()).build();
                List<Map<String, Object>> seed = new ArrayList<Map<String, Object>>();
        seed.add(row("A-1", "墓", 1));
        seed.add(row("A-2", "墓2", 2));
        SyncDiff diff = sync.diff("tomb", seed, keyFn());
        assertEquals(2, diff.getAdded().size());
        assertTrue(diff.getUpdated().isEmpty());
        assertTrue(diff.getRemoved().isEmpty());
    }

    @Test
    public void rememberThenDiffIsEmptyAndChangeProducesThreeStates() throws Exception {
        DataSync sync = DataSync.builder().clientStore(newStore()).build();
        List<Map<String, Object>> base = new ArrayList<Map<String, Object>>();
        base.add(row("A-1", "墓", 1));
        base.add(row("A-2", "墓2", 2));
        sync.remember("tomb", base, keyFn());
        SyncDiff unchanged = sync.diff("tomb", base, keyFn());
        assertTrue("基线未变应无增量", unchanged.isEmpty());

        // 改一行、加一行、删一行
        List<Map<String, Object>> changed = new ArrayList<Map<String, Object>>();
        changed.add(row("A-1", "墓改名", 1));   // UPDATE
        changed.add(row("A-3", "墓3", 3));     // ADD（A-2 被删除 → DELETE）
        SyncDiff diff = sync.diff("tomb", changed, keyFn());
        assertEquals(1, diff.getAdded().size());
        assertEquals("A-3", diff.getAdded().get(0).get("tomb_code"));
        assertEquals(1, diff.getUpdated().size());
        assertEquals("墓改名", diff.getUpdated().get(0).get("tomb_name"));
        assertEquals("A-1", diff.getUpdated().get(0).get("tomb_code"));
        assertEquals(java.util.Collections.singletonList("A-2"), diff.getRemoved());
    }

    @Test
    public void fingerprintStableAcrossRowOrder() throws Exception {
        DataSync sync = DataSync.builder().clientStore(newStore()).build();
        List<Map<String, Object>> a = new ArrayList<Map<String, Object>>();
        a.add(row("A-1", "墓", 1));
        a.add(row("A-2", "墓2", 2));
        List<Map<String, Object>> b = new ArrayList<Map<String, Object>>();
        b.add(row("A-2", "墓2", 2));
        b.add(row("A-1", "墓", 1));
        String fp1 = sync.diff("tomb", a, keyFn()).getFingerprint();
        String fp2 = sync.diff("tomb", b, keyFn()).getFingerprint();
        assertEquals("行顺序不应影响整表指纹", fp1, fp2);
    }

    @Test
    public void snapshotRoundTripViaClientStore() throws Exception {
        ClientStore store = newStore();
        Map<String, String> snapshot = new LinkedHashMap<String, String>();
        snapshot.put(SyncDiff.FINGERPRINT_KEY, "fp-1");
        snapshot.put("A-1", "hash-a1");
        store.saveSyncSnapshot("tomb", snapshot);
        Map<String, String> loaded = store.loadSyncSnapshot("tomb");
        assertEquals("fp-1", loaded.get(SyncDiff.FINGERPRINT_KEY));
        assertEquals("hash-a1", loaded.get("A-1"));
        // 覆盖写：第二次保存只含新行
        Map<String, String> snapshot2 = new LinkedHashMap<String, String>();
        snapshot2.put("A-9", "hash-a9");
        store.saveSyncSnapshot("tomb", snapshot2);
        assertEquals(1, store.loadSyncSnapshot("tomb").size());
    }
}
