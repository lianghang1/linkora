package com.linkora.core.storage.jdbc;

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
 * 版本化 schema 迁移框架（服务端 lk_* 与客户端 lk_client_* 表共用）。
 *
 * <p>自研轻量实现：版本表 + 顺序脚本，不引入 Flyway。SQL 由调用方提供，
 * 保持方言中立（ANSI）；方言差异（索引 DDL、幂等容错规则）由本框架统一处理。
 * 每个迁移版本为语句数组（一条语句一个元素），在事务中执行并记录版本号，
 * 任意时刻崩溃重启后从上次版本继续。</p>
 *
 * <p>方言注意：</p>
 * <ul>
 *   <li><b>H2</b>：{@code CREATE INDEX/ADD COLUMN} 均支持 {@code IF NOT EXISTS}。</li>
 *   <li><b>MySQL</b>：{@code CREATE INDEX} 不支持 {@code IF NOT EXISTS}（索引脚本
 *       按方言裁剪）；DDL 隐式提交，崩溃续跑可能留下"已建表未记版本"的中间态——
 *       放行"对象已存在"类错误（1050 表已存在 / 1060 列已存在 / 1061 索引已存在）。</li>
 *   <li><b>SQLite</b>：与 H2 一样支持 {@code CREATE INDEX IF NOT EXISTS}，但
 *       {@code ADD COLUMN} 不支持 {@code IF NOT EXISTS}——重复列以
 *       "duplicate column name" 容错重跑。</li>
 * </ul>
 *
 * <p>宿主事务接管：连接处于手动提交（宿主事务内）时，迁移加入当前事务，
 * 提交/回滚由宿主裁决，本框架严禁中途 commit/rollback。</p>
 *
 * @author lianghang
 */
public final class SchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);

    /** 支持的数据库方言：迁移脚本选择 + 重跑幂等容错规则。 */
    public enum Dialect {
        H2, MYSQL, SQLITE
    }

    private SchemaMigrator() {
    }

    /** 探测连接对应的方言；无法识别时抛出 {@link LinkoraException}。 */
    public static Dialect dialectOf(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product == null) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "无法识别数据库产品（DatabaseProductName 为 null）");
        }
        if (product.contains("MySQL")) {
            return Dialect.MYSQL;
        }
        if (product.contains("SQLite")) {
            return Dialect.SQLITE;
        }
        if (product.contains("H2")) {
            return Dialect.H2;
        }
        throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                "不支持的数据库方言: " + product + "（当前支持 H2 / MySQL / SQLite）");
    }

    /**
     * 依次应用未执行的迁移；重复调用幂等。
     *
     * @param dataSource   数据源
     * @param versionTable 版本记录表名（如 lk_schema_version / lk_client_schema_version）
     * @param migrations   迁移脚本，migrations[i] 为第 (i+1) 版的语句数组
     * @return 迁移后到达的最新版本号
     */
    public static int migrate(DataSource dataSource, String versionTable, String[][] migrations) {
        try (Connection connection = dataSource.getConnection()) {
            Dialect dialect = dialectOf(connection);
            createVersionTable(connection, versionTable);
            int current = currentVersion(connection, versionTable);
            for (int version = current + 1; version <= migrations.length; version++) {
                applyMigration(connection, versionTable, version,
                        migrations[version - 1], dialect);
            }
            return migrations.length;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR,
                    "schema 迁移失败: " + versionTable, e);
        }
    }

    /** 方言级幂等容错：崩溃续跑/重放时"对象已存在"类错误视为命中。 */
    static boolean isIdempotentHit(SQLException e, Dialect dialect) {
        if (dialect == Dialect.MYSQL) {
            // 1050 表已存在 / 1060 列已存在 / 1061 索引已存在（DDL 隐式提交）
            return e.getErrorCode() == 1050 || e.getErrorCode() == 1060
                    || e.getErrorCode() == 1061;
        }
        if (dialect == Dialect.SQLITE) {
            // ADD COLUMN 重复执行（SQLite 无 ADD COLUMN IF NOT EXISTS）
            String msg = e.getMessage();
            return msg != null && msg.contains("duplicate column name");
        }
        return false;
    }

    private static void createVersionTable(Connection connection, String versionTable)
            throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + versionTable + " ("
                    + " version     BIGINT NOT NULL PRIMARY KEY,"
                    + " applied_at  BIGINT NOT NULL)");
        }
    }

    private static int currentVersion(Connection connection, String versionTable)
            throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM " + versionTable)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static void applyMigration(Connection connection, String versionTable,
                                       int version, String[] statements, Dialect dialect)
            throws SQLException {
        // 宿主事务接管模式（连接已在手动提交）：迁移加入当前事务，提交/回滚由
        // 宿主裁决，严禁中途 commit/rollback（否则提前提交宿主业务写入）；
        // 自管模式（自动提交连接）保持原行为：本地事务自行提交。
        boolean localTx = connection.getAutoCommit();
        if (localTx) {
            connection.setAutoCommit(false);
        }
        try {
            for (String sql : statements) {
                try (Statement st = connection.createStatement()) {
                    st.execute(sql);
                } catch (SQLException e) {
                    if (isIdempotentHit(e, dialect)) {
                        log.info("迁移对象已存在（{} 幂等命中）: {}", dialect, sql);
                        continue;
                    }
                    throw e;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO " + versionTable + " (version, applied_at) VALUES (?, ?)")) {
                ps.setInt(1, version);
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            }
            if (localTx) {
                connection.commit();
            }
            log.info("已应用 schema 迁移 v{}（{}）", version, versionTable);
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
