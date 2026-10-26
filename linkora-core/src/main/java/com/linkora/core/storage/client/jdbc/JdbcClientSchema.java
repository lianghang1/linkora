package com.linkora.core.storage.client.jdbc;

import com.linkora.core.storage.jdbc.SchemaMigrator;

import javax.sql.DataSource;

/**
 * 客户端（datasource 模式）schema 迁移：宿主库中的三张客户端表
 * {@code lk_client_outbox / lk_client_inbox / lk_client_meta}，
 * DDL 定义 + 委托共用迁移框架 {@link SchemaMigrator} 执行。
 *
 * <p>与内嵌 SQLite 模式（SqliteClientStore + SqliteSchema，表名 lk_outbox 等）
 * 的表名刻意区分（lk_client_* 前缀），避免宿主库中的命名歧义；
 * 列结构与内嵌模式一致，语义契约见 {@code ClientStore}。</p>
 *
 * <p>方言：SQL 方言中立；MySQL 索引无 IF NOT EXISTS 由迁移框架容错保证幂等。</p>
 *
 * @author lianghang
 */
public final class JdbcClientSchema {

    /** 当前最新 schema 版本，等于 MIGRATIONS 数组长度。 */
    public static final int LATEST_VERSION = 3;

    private static final String VERSION_TABLE = "lk_client_schema_version";

    /** v1：三张客户端表 + 索引（方言通用 IF NOT EXISTS + MySQL 容错）。 */
    private static final String[] V1 = {
            "CREATE TABLE IF NOT EXISTS lk_client_outbox ("
                    + " message_id    VARCHAR(64)  PRIMARY KEY,"
                    + " message_type  VARCHAR(255) NOT NULL,"
                    + " payload       BLOB         NOT NULL,"
                    + " status        VARCHAR(32)  NOT NULL,"
                    + " created_at    BIGINT       NOT NULL,"
                    + " updated_at    BIGINT       NOT NULL,"
                    + " retry_count   INT          NOT NULL DEFAULT 0,"
                    + " next_retry_at BIGINT,"
                    + " expires_at    BIGINT,"
                    + " last_error    TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_client_outbox_status_retry "
                    + "ON lk_client_outbox (status, next_retry_at)",
            "CREATE TABLE IF NOT EXISTS lk_client_inbox ("
                    + " message_id     VARCHAR(64)  PRIMARY KEY,"
                    + " message_type   VARCHAR(255) NOT NULL,"
                    + " payload        BLOB         NOT NULL,"
                    + " receive_status VARCHAR(32)  NOT NULL,"
                    + " process_status VARCHAR(32)  NOT NULL,"
                    + " received_at    BIGINT       NOT NULL,"
                    + " processed_at   BIGINT,"
                    + " retry_count    INT          NOT NULL DEFAULT 0,"
                    + " next_retry_at  BIGINT,"
                    + " last_error     TEXT)",
            "CREATE INDEX IF NOT EXISTS idx_client_inbox_recv_ack "
                    + "ON lk_client_inbox (receive_status, received_at)",
            "CREATE INDEX IF NOT EXISTS idx_client_inbox_proc_retry "
                    + "ON lk_client_inbox (process_status, next_retry_at)",
            "CREATE TABLE IF NOT EXISTS lk_client_meta ("
                    + " meta_key   VARCHAR(128) PRIMARY KEY,"
                    + " meta_value TEXT NOT NULL)",
    };

    /** v2：顺序键（对齐内嵌模式的演进路径）。ADD COLUMN 幂等，索引支撑让路查询。 */
    private static final String[] V2 = {
            "ALTER TABLE lk_client_outbox ADD COLUMN sequence_key VARCHAR(128)",
            "ALTER TABLE lk_client_inbox ADD COLUMN sequence_key VARCHAR(128)",
            "CREATE INDEX IF NOT EXISTS idx_client_outbox_seq "
                    + "ON lk_client_outbox (sequence_key)",
    };

    /** v3：数据同步快照表（0.6.0 sync 工具）。 */
    private static final String[] V3 = {
            "CREATE TABLE IF NOT EXISTS lk_client_snapshot ("
                    + " sync_key  VARCHAR(128) NOT NULL,"
                    + " row_key   VARCHAR(255) NOT NULL,"
                    + " row_hash  VARCHAR(64)  NOT NULL,"
                    + " PRIMARY KEY (sync_key, row_key))",
    };

    private static final String[][] MIGRATIONS = {V1, V2, V3};

    private JdbcClientSchema() {
    }

    /** 依次应用未执行的迁移；重复调用幂等。 */
    public static void migrate(DataSource dataSource) {
        SchemaMigrator.migrate(dataSource, VERSION_TABLE, MIGRATIONS);
    }
}
