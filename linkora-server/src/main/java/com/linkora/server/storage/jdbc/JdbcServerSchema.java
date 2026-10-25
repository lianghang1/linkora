package com.linkora.server.storage.jdbc;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;
import com.linkora.core.storage.jdbc.SchemaMigrator;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 服务端 schema 迁移：DDL 定义 + 委托共用迁移框架 {@link SchemaMigrator} 执行。
 *
 * <p>SQL 保持方言中立（ANSI），已验证 H2、SQLite 与 MySQL（5.7 / 8，见
 * {@code JdbcServerStorageMySqlIT}）。方言差异（索引 DDL 裁剪、幂等容错）
 * 由 {@code SchemaMigrator} 统一处理，本类只按方言提供语句数组。</p>
 *
 * <p>版本要点：v1 建表建索引；v2 payload 扩容（MySQL 64KB → LONGTEXT，
 * H2 → CLOB，SQLite TEXT 无长度限制为 no-op）；v3 顺序键；v4 人类可读元数据
 * （消息主题 subject、客户端显示名 display_name）。各版本重复执行均幂等。</p>
 *
 * @author lianghang
 */
public final class JdbcServerSchema {

    /** 当前最新 schema 版本，等于 MIGRATIONS 数组长度。 */
    public static final int LATEST_VERSION = 4;

    private static final String VERSION_TABLE = "lk_schema_version";

    /** v1 表结构（方言通用：CREATE TABLE IF NOT EXISTS 各库均支持）。 */
    private static final String[] V1_TABLES = {
            "CREATE TABLE IF NOT EXISTS lk_client ("
                    + " client_id        VARCHAR(128) PRIMARY KEY,"
                    + " enabled          BOOLEAN      NOT NULL,"
                    + " created_at       BIGINT       NOT NULL,"
                    + " connection_state VARCHAR(16)  NOT NULL,"
                    + " last_active_at   BIGINT)",
            "CREATE TABLE IF NOT EXISTS lk_client_seq ("
                    + " client_id VARCHAR(128) PRIMARY KEY,"
                    + " last_seq  BIGINT       NOT NULL)",
            "CREATE TABLE IF NOT EXISTS lk_client_cursor ("
                    + " client_id VARCHAR(128) PRIMARY KEY,"
                    + " last_seq  BIGINT       NOT NULL)",
            "CREATE TABLE IF NOT EXISTS lk_message ("
                    + " message_id     VARCHAR(64)  PRIMARY KEY,"
                    + " client_id      VARCHAR(128) NOT NULL,"
                    + " message_type   VARCHAR(255) NOT NULL,"
                    + " payload_base64 TEXT         NOT NULL,"
                    + " created_at     BIGINT       NOT NULL,"
                    + " expires_at     BIGINT,"
                    + " schema_version INT          NOT NULL,"
                    + " trace_id       VARCHAR(128))",
            "CREATE TABLE IF NOT EXISTS lk_delivery ("
                    + " message_id    VARCHAR(64)  NOT NULL,"
                    + " client_id     VARCHAR(128) NOT NULL,"
                    + " seq           BIGINT       NOT NULL,"
                    + " state         VARCHAR(32)  NOT NULL,"
                    + " retry_count   INT          NOT NULL DEFAULT 0,"
                    + " next_retry_at BIGINT,"
                    + " last_error    TEXT,"
                    + " created_at    BIGINT       NOT NULL,"
                    + " updated_at    BIGINT       NOT NULL,"
                    + " PRIMARY KEY (message_id, client_id))",
    };

    /** H2 / SQLite：CREATE INDEX 支持 IF NOT EXISTS。 */
    private static final String[] V1_INDEXES_ANSI = {
            "CREATE INDEX IF NOT EXISTS idx_delivery_client_state ON lk_delivery (client_id, state, seq)",
            "CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_client_seq ON lk_delivery (client_id, seq)",
            "CREATE INDEX IF NOT EXISTS idx_message_client_created ON lk_message (client_id, created_at)",
    };

    /** MySQL：CREATE INDEX 不支持 IF NOT EXISTS，重跑幂等由"对象已存在"容错保证。 */
    private static final String[] V1_INDEXES_MYSQL = {
            "CREATE INDEX idx_delivery_client_state ON lk_delivery (client_id, state, seq)",
            "CREATE UNIQUE INDEX uk_delivery_client_seq ON lk_delivery (client_id, seq)",
            "CREATE INDEX idx_message_client_created ON lk_message (client_id, created_at)",
    };

    /**
     * v2：payload 列扩容。v1 的 TEXT 在 MySQL 上限 64KB，而 payload 走 Base64
     * （膨胀 4/3）且 body 上限 1MB——原始 payload 超约 48KB 就写库失败。
     * MySQL 用 LONGTEXT（4GB）；H2 用 CLOB；SQLite TEXT 无长度限制，no-op。
     * MODIFY / SET DATA TYPE 重复执行均幂等，崩溃重放安全。
     */
    private static final String[] V2_H2 = {
            "ALTER TABLE lk_message ALTER COLUMN payload_base64 CLOB NOT NULL",
    };
    private static final String[] V2_MYSQL = {
            "ALTER TABLE lk_message MODIFY COLUMN payload_base64 LONGTEXT NOT NULL",
    };
    private static final String[] V2_SQLITE = {};

    /**
     * v3：顺序键。lk_message 存键（推送时线路 JSON 需要还原它），
     * lk_delivery 存键（同 (client_id, sequence_key) 下按 seq 单调投递，
     * 前一条到终态前不推后一条）；索引支撑"同 key 是否有更早未终结记录"的
     * NOT EXISTS 判断。ADD COLUMN 重复执行均幂等，崩溃重放安全。
     */
    private static final String[] V3_H2 = {
            "ALTER TABLE lk_message ADD COLUMN IF NOT EXISTS sequence_key VARCHAR(128)",
            "ALTER TABLE lk_delivery ADD COLUMN IF NOT EXISTS sequence_key VARCHAR(128)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_client_key_seq "
                    + "ON lk_delivery (client_id, sequence_key, seq)",
    };
    private static final String[] V3_MYSQL = {
            "ALTER TABLE lk_message ADD COLUMN sequence_key VARCHAR(128)",
            "ALTER TABLE lk_delivery ADD COLUMN sequence_key VARCHAR(128)",
            "CREATE INDEX idx_delivery_client_key_seq "
                    + "ON lk_delivery (client_id, sequence_key, seq)",
    };
    private static final String[] V3_SQLITE = {
            "ALTER TABLE lk_message ADD COLUMN sequence_key VARCHAR(128)",
            "ALTER TABLE lk_delivery ADD COLUMN sequence_key VARCHAR(128)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_client_key_seq "
                    + "ON lk_delivery (client_id, sequence_key, seq)",
    };

    /** v4：人类可读元数据——消息主题（运维界面直接展示）、客户端显示名。幂等。 */
    private static final String[] V4_H2 = {
            "ALTER TABLE lk_message ADD COLUMN IF NOT EXISTS subject VARCHAR(255)",
            "ALTER TABLE lk_client ADD COLUMN IF NOT EXISTS display_name VARCHAR(255)",
    };
    private static final String[] V4_MYSQL = {
            "ALTER TABLE lk_message ADD COLUMN subject VARCHAR(255)",
            "ALTER TABLE lk_client ADD COLUMN display_name VARCHAR(255)",
    };
    private static final String[] V4_SQLITE = {
            "ALTER TABLE lk_message ADD COLUMN subject VARCHAR(255)",
            "ALTER TABLE lk_client ADD COLUMN display_name VARCHAR(255)",
    };

    private static final String[][] MIGRATIONS =
            {concat(V1_TABLES, V1_INDEXES_ANSI), V2_H2, V3_H2, V4_H2};
    private static final String[][] MIGRATIONS_MYSQL =
            {concat(V1_TABLES, V1_INDEXES_MYSQL), V2_MYSQL, V3_MYSQL, V4_MYSQL};
    private static final String[][] MIGRATIONS_SQLITE =
            {concat(V1_TABLES, V1_INDEXES_ANSI), V2_SQLITE, V3_SQLITE, V4_SQLITE};

    private static String[] concat(String[] tables, String[] indexes) {
        String[] all = new String[tables.length + indexes.length];
        System.arraycopy(tables, 0, all, 0, tables.length);
        System.arraycopy(indexes, 0, all, tables.length, indexes.length);
        return all;
    }

    private JdbcServerSchema() {
    }

    /** 依次应用未执行的迁移；重复调用幂等。 */
    public static void migrate(DataSource dataSource) {
        try {
            SchemaMigrator.migrate(dataSource, VERSION_TABLE, selectMigrations(dataSource));
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "服务端 schema 迁移失败", e);
        }
    }

    private static String[][] selectMigrations(DataSource dataSource) throws SQLException {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            SchemaMigrator.Dialect dialect = SchemaMigrator.dialectOf(connection);
            switch (dialect) {
                case MYSQL:
                    return MIGRATIONS_MYSQL;
                case SQLITE:
                    return MIGRATIONS_SQLITE;
                case H2:
                default:
                    return MIGRATIONS;
            }
        }
    }
}
