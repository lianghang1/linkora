package com.linkora.core.storage.jdbc;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * JDBC 完整性约束违反判断工具（各数据库驱动的异常映射差异收敛点）。
 *
 * <p>"先 UPDATE 不命中再 INSERT、主键冲突视为幂等命中"是各仓储的通用模式；
 * 但约束冲突的异常类型因驱动而异：H2 / MySQL 抛标准
 * {@link SQLIntegrityConstraintViolationException}，SQLite xerial 抛
 * {@code SQLiteException}（不继承标准类型）——统一用本工具判断，
 * 依次检查：标准异常类型 → SQLState 23xxx（完整性约束违反标准段）→
 * 常见驱动报文关键词。</p>
 *
 * @author lianghang
 */
public final class JdbcErrors {

    private JdbcErrors() {
    }

    /** 是否为完整性约束违反（主键 / 唯一键冲突等）。 */
    public static boolean isIntegrityViolation(SQLException e) {
        if (e instanceof SQLIntegrityConstraintViolationException) {
            return true;
        }
        String sqlState = e.getSQLState();
        if (sqlState != null && sqlState.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null && (message.contains("UNIQUE constraint failed")
                || message.contains("PRIMARY KEY must be unique")
                || message.contains("duplicate key")
                || message.contains("Duplicate entry")
                || message.contains("重复的键")
                || message.contains("唯一约束"));
    }
}
