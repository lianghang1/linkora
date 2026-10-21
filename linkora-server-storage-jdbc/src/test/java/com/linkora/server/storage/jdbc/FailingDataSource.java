package com.linkora.server.storage.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * 测试用故障注入 DataSource：getConnection 永远抛出 SQLException。
 */
final class FailingDataSource implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
        throw new SQLException("注入故障：存储不可用");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("注入故障：存储不可用");
    }

    @Override
    public <T> T unwrap(Class<T> iface) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // 忽略
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // 忽略
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }
}
