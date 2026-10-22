package com.linkora.client.storage.sqlite;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 客户端 SQLite schema 迁移（自研轻量实现：版本表 + 顺序脚本）。
 *
 * <p>每个迁移脚本在独立事务中执行并记录版本号；进程在任意脚本执行中崩溃后，
 * 重启会从上次已提交的版本继续，保证迁移幂等且不会半途处于中间状态。</p>
 *
 * @author lianghang
 */
final class SqliteSchema {

    private static final Logger log = LoggerFactory.getLogger(SqliteSchema.class);

    /** 当前最新 schema 版本，等于 MIGRATIONS 数组长度。 */
    static final int LATEST_VERSION = 1;

    private static final String CREATE_VERSION_TABLE =
            "CREATE TABLE IF NOT EXISTS lk_schema_version ("
                    + " version     INTEGER PRIMARY KEY,"
                    + " applied_at  INTEGER NOT NULL)";

    private static final String V1 = ""
            + "CREATE TABLE IF NOT EXISTS lk_outbox ("
            + "  message_id    TEXT PRIMARY KEY,"
            + "  message_type  TEXT NOT NULL,"
            + "  payload       BLOB NOT NULL,"
            + "  status        TEXT NOT NULL,"
            + "  created_at    INTEGER NOT NULL,"
            + "  updated_at    INTEGER NOT NULL,"
            + "  retry_count   INTEGER NOT NULL DEFAULT 0,"
            + "  next_retry_at INTEGER,"
            + "  expires_at    INTEGER,"
            + "  last_error    TEXT"
            + ");"
            + "CREATE INDEX IF NOT EXISTS idx_outbox_status_retry ON lk_outbox (status, next_retry_at);"
            + "CREATE INDEX IF NOT EXISTS idx_outbox_cleanup ON lk_outbox (status, updated_at);"
            + "CREATE TABLE IF NOT EXISTS lk_inbox ("
            + "  message_id     TEXT PRIMARY KEY,"
            + "  message_type   TEXT NOT NULL,"
            + "  payload        BLOB NOT NULL,"
            + "  receive_status TEXT NOT NULL,"
            + "  process_status TEXT NOT NULL,"
            + "  received_at    INTEGER NOT NULL,"
            + "  processed_at   INTEGER,"
            + "  retry_count    INTEGER NOT NULL DEFAULT 0,"
            + "  next_retry_at  INTEGER,"
            + "  last_error     TEXT"
            + ");"
            + "CREATE INDEX IF NOT EXISTS idx_inbox_recv_ack ON lk_inbox (receive_status, received_at);"
            + "CREATE INDEX IF NOT EXISTS idx_inbox_proc_retry ON lk_inbox (process_status, next_retry_at);"
            + "CREATE INDEX IF NOT EXISTS idx_inbox_cleanup ON lk_inbox (receive_status, process_status);"
            + "CREATE TABLE IF NOT EXISTS lk_meta ("
            + "  meta_key   TEXT PRIMARY KEY,"
            + "  meta_value TEXT NOT NULL"
            + ");";

    private static final String[] MIGRATIONS = {V1};

    private SqliteSchema() {
    }

    /** 依次应用未执行的迁移；重复调用幂等。 */
    static void migrate(Connection connection) throws SQLException {
        createVersionTable(connection);
        int current = currentVersion(connection);
        for (int version = current + 1; version <= MIGRATIONS.length; version++) {
            applyMigration(connection, version, MIGRATIONS[version - 1]);
        }
    }

    private static void createVersionTable(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(CREATE_VERSION_TABLE);
        }
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM lk_schema_version")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void applyMigration(Connection connection, int version, String script)
            throws SQLException {
        // 宿主事务接管模式（连接已在手动提交）：迁移加入当前事务，提交/回滚
        // 由宿主裁决，严禁中途 commit/rollback（否则提前提交宿主业务写入）；
        // 自管模式（自动提交连接）保持原行为：本地事务自行提交。
        boolean localTx = connection.getAutoCommit();
        if (localTx) {
            connection.setAutoCommit(false);
        }
        try {
            for (String statement : script.split(";")) {
                String sql = statement.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                try (Statement st = connection.createStatement()) {
                    st.execute(sql);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lk_schema_version (version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            if (localTx) {
                connection.commit();
            }
            log.info("已应用客户端 SQLite schema 迁移 v{}", version);
        } catch (SQLException e) {
            if (localTx) {
                connection.rollback();
            }
            throw e;
        } finally {
            if (localTx) {
                connection.setAutoCommit(true);
            }
        }
    }
}
