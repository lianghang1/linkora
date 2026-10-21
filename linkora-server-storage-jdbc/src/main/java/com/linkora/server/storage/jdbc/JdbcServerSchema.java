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
 * <p>SQL 保持方言中立（ANSI），已验证 H2；MySQL 8 / PostgreSQL 在后续三库矩阵阶段验证。
 * 每个迁移脚本在独立事务中执行并记录版本号，任意时刻崩溃重启后从上次版本继续。</p>
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

    private static final String V1 = ""
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
            + ");"
            + "CREATE INDEX IF NOT EXISTS idx_delivery_client_state ON lk_delivery (client_id, state, seq);"
            + "CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_client_seq ON lk_delivery (client_id, seq);"
            + "CREATE INDEX IF NOT EXISTS idx_message_client_created ON lk_message (client_id, created_at);";

    private static final String[] MIGRATIONS = {V1};

    private JdbcServerSchema() {
    }

    /** 依次应用未执行的迁移；重复调用幂等。 */
    public static void migrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            createVersionTable(connection);
            int current = currentVersion(connection);
            for (int version = current + 1; version <= MIGRATIONS.length; version++) {
                applyMigration(connection, version, MIGRATIONS[version - 1]);
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "服务端 schema 迁移失败", e);
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
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
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
                ps.setLong(1, version);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            connection.commit();
            log.info("已应用服务端 schema 迁移 v{}", version);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }
}
