package com.linkora.example.bootclient.service;

import com.linkora.client.UplinkPublisher;
import com.linkora.client.sync.DataSync;
import com.linkora.client.sync.SnapshotDiffer;
import com.linkora.client.sync.SyncDiff;
import com.linkora.core.storage.client.ClientStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 墓位数据同步（0.6.0，模拟墓园真实场景）：业务系统不改造，本客户端旁挂部署，
 * 定时【只读业务库全量 → 客户端快照 diff → 只上报增量】给总部。
 *
 * <p>业务库为独立的 SQLite 文件（业务系统"本来就有"的库，本客户端只读），
 * 切换为只读 MySQL 仅改数据源连接。</p>
 *
 * @author lianghang
 */
@Service
public class TombSyncService {

    private static final Logger log = LoggerFactory.getLogger(TombSyncService.class);

    /** 上行 topic：总部 TombSyncHandler 按此类型处理。 */
    public static final String TOPIC = "tomb-sync";

    private final ClientStore clientStore;
    private final UplinkPublisher uplink;
    private final JdbcTemplate businessDb;
    private final AtomicInteger rounds = new AtomicInteger();
    private volatile DataSync dataSync;

    public TombSyncService(ClientStore clientStore, UplinkPublisher uplink,
                           @Qualifier("tombBusinessDataSource") DataSource businessDataSource) {
        this.clientStore = clientStore;
        this.uplink = uplink;
        this.businessDb = new JdbcTemplate(businessDataSource);
        this.businessDb.execute("CREATE TABLE IF NOT EXISTS tomb ("
                + " tomb_code   VARCHAR(64) PRIMARY KEY,"
                + " tomb_name   VARCHAR(255),"
                + " area        VARCHAR(64),"
                + " updated_at  BIGINT)");
    }

    private DataSync dataSync() {
        if (dataSync == null) {
            synchronized (this) {
                if (dataSync == null) {
                    dataSync = DataSync.builder().clientStore(clientStore).build();
                }
            }
        }
        return dataSync;
    }

    /** 定时同步：全量读 → diff → 只发增量 → remember 落快照。 */
    @Scheduled(fixedDelayString = "${sync.interval-ms:30000}")
    public void scheduledSync() {
        try {
            List<Map<String, Object>> rows = businessDb.queryForList(
                    "SELECT tomb_code, tomb_name, area, updated_at FROM tomb");
            SyncDiff diff = dataSync().diffAndPublish(uplink, "tomb", TOPIC, rows,
                    new SnapshotDiffer.RowKeyFn() {
                        @Override
                        public String key(Map<String, Object> row) {
                            return String.valueOf(row.get("tomb_code"));
                        }
                    });
            log.info("[定时同步 #{}] 全量读取 {} 行，增量 {}（+{} ~{} -{}）",
                    rounds.incrementAndGet(), rows.size(), diff.size(),
                    diff.getAdded().size(), diff.getUpdated().size(), diff.getRemoved().size());
        } catch (RuntimeException e) {
            log.warn("定时同步失败（下轮重试）: {}", e.getMessage());
        }
    }

    /** 演示用：模拟业务系统的墓位数据变化（真实场景在业务系统自己的库里）。 */
    @RestController
    public static class BusinessController {
        private final TombSyncService service;

        public BusinessController(TombSyncService service) {
            this.service = service;
        }

        @PostMapping("/api/business/tomb")
        public Map<String, Object> upsert(@RequestParam String code, @RequestParam String name,
                                          @RequestParam(defaultValue = "A区") String area) {
            service.businessDb.update("INSERT INTO tomb (tomb_code, tomb_name, area, updated_at) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT(tomb_code) DO UPDATE SET "
                            + "tomb_name = excluded.tomb_name, area = excluded.area, "
                            + "updated_at = excluded.updated_at",
                    code, name, area, System.currentTimeMillis());
            return java.util.Collections.<String, Object>singletonMap("code", code);
        }

        @PostMapping("/api/business/tomb/delete")
        public Map<String, Object> delete(@RequestParam String code) {
            service.businessDb.update("DELETE FROM tomb WHERE tomb_code = ?", code);
            return java.util.Collections.<String, Object>singletonMap("deleted", true);
        }

        @GetMapping("/api/business/tomb")
        public List<Map<String, Object>> all() {
            return service.businessDb.queryForList(
                    "SELECT tomb_code, tomb_name, area FROM tomb");
        }
    }
}
