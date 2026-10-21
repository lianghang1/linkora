package com.linkora.example.txclient;

import com.linkora.core.error.LinkoraErrorCode;
import com.linkora.core.error.LinkoraException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 宿主侧 ClientStore 表结构（H2 方言）。生产宿主可把同样的结构搬进
 * 自己的迁移体系（Flyway 等），或复用已有业务表。
 *
 * @author lianghang
 */
final class TxClientSchema {

    private static final String DDL = ""
            + "CREATE TABLE IF NOT EXISTS lk_outbox ("
            + "  message_id    VARCHAR(64)  PRIMARY KEY,"
            + "  message_type  VARCHAR(255) NOT NULL,"
            + "  payload       BLOB         NOT NULL,"
            + "  status        VARCHAR(32)  NOT NULL,"
            + "  created_at    BIGINT       NOT NULL,"
            + "  updated_at    BIGINT       NOT NULL,"
            + "  retry_count   INT          NOT NULL DEFAULT 0,"
            + "  next_retry_at BIGINT,"
            + "  expires_at    BIGINT,"
            + "  last_error    VARCHAR(2048)"
            + ");"
            + "CREATE TABLE IF NOT EXISTS lk_inbox ("
            + "  message_id     VARCHAR(64)  PRIMARY KEY,"
            + "  message_type   VARCHAR(255) NOT NULL,"
            + "  payload        BLOB         NOT NULL,"
            + "  receive_status VARCHAR(32)  NOT NULL,"
            + "  process_status VARCHAR(32)  NOT NULL,"
            + "  received_at    BIGINT       NOT NULL,"
            + "  processed_at   BIGINT,"
            + "  retry_count    INT          NOT NULL DEFAULT 0,"
            + "  next_retry_at  BIGINT,"
            + "  last_error     VARCHAR(2048)"
            + ");"
            + "CREATE TABLE IF NOT EXISTS lk_meta ("
            + "  meta_key   VARCHAR(128) PRIMARY KEY,"
            + "  meta_value VARCHAR(4096) NOT NULL"
            + ");"
            + "CREATE INDEX IF NOT EXISTS idx_outbox_status ON lk_outbox (status, next_retry_at);"
            + "CREATE INDEX IF NOT EXISTS idx_inbox_receive ON lk_inbox (receive_status, received_at);"
            + "CREATE INDEX IF NOT EXISTS idx_inbox_process ON lk_inbox (process_status, next_retry_at);";

    private TxClientSchema() {
    }

    /** 幂等建表；在原始 DataSource 上执行（非事务代理连接）。 */
    static void migrate(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement st = connection.createStatement()) {
            for (String sql : DDL.split(";")) {
                if (!sql.trim().isEmpty()) {
                    st.execute(sql.trim());
                }
            }
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "客户端宿主库 schema 迁移失败", e);
        }
    }
}
