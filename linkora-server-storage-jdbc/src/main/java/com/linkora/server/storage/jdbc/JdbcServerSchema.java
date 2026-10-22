package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 服务端 schema 迁移（自研轻量实现：版本表 + 顺序脚本，不引入 Flyway）。
 *
 * <p>SQL 保持方言中立（ANSI），已验证 H2 与 MySQL 8（见
 * {@code JdbcServerStorageMySqlIT}）；PostgreSQL 在后续三库矩阵阶段验证。
 * 每个迁移脚本在独立事务中执行并记录版本号，任意时刻崩溃重启后从上次版本继续。</p>
 *
 * <p>MySQL 方言注意两点：① {@code CREATE INDEX} 不支持 {@code IF NOT EXISTS}，
 * 索引脚本按方言裁剪；② DDL 隐式提交，崩溃续跑可能留下"已建表未记版本"的
 * 中间态——对 MySQL 放行"对象已存在"类错误（1050 表已存在 / 1061 索引已存在）
 * 保证重跑幂等。</p>
 *
 * @author lianghang
 */
public final class JdbcServerSchema {

    private static final Logger log = LoggerFactory.getLogger(JdbcServerSchema.class);

    /** 当前最新 schema 版本，等于 MIGRATIONS 数组长度。 */
    public static final int LATEST_VERSION = 1;

    private static final String CREATE_VERSION_TABLE =
            "CREATE TABLE IF NOT EXISTS lk_schema_version ("
                    + " version     BIGINT NOT NULL PRIMARY KEY,"
                    + " applied_at  BIGINT NOT NULL)";

    /** 表结构（两种方言通用：CREATE TABLE IF NOT EXISTS 均 supported）。 */
    private static final String V1_TABLES = ""
            + "CREATE TABLE IF NOT EXISTS lk_client ("
            + "  client_id        VARCHAR(128) PRIMARY KEY,"
            + "  enabled          BOOLEAN      NOT NULL,"
            + "  created_at       BIGINT       NOT NULL,"
            + "  connection_state VARCHAR(16)  NOT NULL,"
            + "  last_active_at   BIGINT"
            + ");"
            + "CREATE TABLE IF NOT EXISTS lk_client_seq ("
            + "  client_id VARCHAR(128) PRIMARY KEY,"
            + "  last_seq  BIGINT       NOT NULL"
            + ");"
            + "CREATE TABLE IF NOT EXISTS lk_client_cursor ("
            + "  client_id VARCHAR(128) PRIMARY KEY,"
            + "  last_seq  BIGINT       NOT NULL"
            + ");"
            + "CREATE TABLE IF NOT EXISTS lk_message ("
            + "  message_id     VARCHAR(64)  PRIMARY KEY,"
            + "  client_id      VARCHAR(128) NOT NULL,"
            + "  message_type   VARCHAR(255) NOT NULL,"
            + "  payload_base64 TEXT         NOT NULL,"
            + "  created_at     BIGINT       NOT NULL,"
            + "  expires_at     BIGINT,"
            + "  schema_version INT          NOT NULL,"
            + "  trace_id       VARCHAR(128)"
            + ");"
            + "CREATE TABLE IF NOT EXISTS lk_delivery ("
            + "  message_id    VARCHAR(64)  NOT NULL,"
            + "  client_id     VARCHAR(128) NOT NULL,"
            + "  seq           BIGINT       NOT NULL,"
            + "  state         VARCHAR(32)  NOT NULL,"
            + "  retry_count   INT          NOT NULL DEFAULT 0,"
            + "  next_retry_at BIGINT,"
            + "  last_error    TEXT,"
            + "  created_at    BIGINT       NOT NULL,"
            + "  updated_at    BIGINT       NOT NULL,"
            + "  PRIMARY KEY (message_id, client_id)"
            + ");";

    /** H2：CREATE INDEX 支持 IF NOT EXISTS。 */
    private static final String V1_INDEXES_H2 = ""
            + "CREATE INDEX IF NOT EXISTS idx_delivery_client_state ON lk_delivery (client_id, state, seq);"
            + "CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_client_seq ON lk_delivery (client_id, seq);"
            + "CREATE INDEX IF NOT EXISTS idx_message_client_created ON lk_message (client_id, created_at);";

    /** MySQL 8：CREATE INDEX 不支持 IF NOT EXISTS，重跑幂等由"对象已存在"容错保证。 */
    private static final String V1_INDEXES_MYSQL = ""
            + "CREATE INDEX idx_delivery_client_state ON lk_delivery (client_id, state, seq);"
            + "CREATE UNIQUE INDEX uk_delivery_client_seq ON lk_delivery (client_id, seq);"
            + "CREATE INDEX idx_message_client_created ON lk_message (client_id, created_at);";

    private static final String[] MIGRATIONS = {V1_TABLES + V1_INDEXES_H2};
    private static final String[] MIGRATIONS_MYSQL = {V1_TABLES + V1_INDEXES_MYSQL};

    private JdbcServerSchema() {
    }

    /** 依次应用未执行的迁移；重复调用幂等。 */
    public static void migrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            boolean mysql = isMySql(connection);
            String[] migrations = mysql ? MIGRATIONS_MYSQL : MIGRATIONS;
            createVersionTable(connection);
            int current = currentVersion(connection);
            for (int version = current + 1; version <= migrations.length; version++) {
                applyMigration(connection, version, migrations[version - 1], mysql);
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "服务端 schema 迁移失败", e);
        }
    }

    private static boolean isMySql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        return product != null && product.contains("MySQL");
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

    private static void applyMigration(Connection connection, int version, String script,
                                       boolean mysql) throws SQLException {
        // 宿主事务接管模式（连接已在手动提交）：迁移加入当前事务，提交/回滚由
        // 宿主裁决，严禁中途 commit/rollback（否则提前提交宿主业务写入）；
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
                } catch (SQLException e) {
                    // MySQL DDL 隐式提交：崩溃续跑时对象可能已存在，视为幂等命中
                    if (mysql && (e.getErrorCode() == 1050 || e.getErrorCode() == 1061)) {
                        log.info("迁移对象已存在（MySQL 崩溃续跑幂等命中）: {}", sql);
                        continue;
                    }
                    throw e;
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
            log.info("已应用服务端 schema 迁移 v{}", version);
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
