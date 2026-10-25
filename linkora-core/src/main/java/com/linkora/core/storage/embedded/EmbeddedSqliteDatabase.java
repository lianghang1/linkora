package com.linkora.core.storage.embedded;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.io.PrintWriter;

/**
 * 内嵌 SQLite 文件库（两端共用的"store=sqlite"模式基建）。
 *
 * <p>职责：打开/创建本地 SQLite 文件、执行库级初始化 PRAGMA（journal_mode=WAL
 * 持久化在库文件中，设置一次即可）、并以 {@link DataSource} 形式对外提供连接。
 * 每个连接取出时补齐<strong>逐连接生效</strong>的 PRAGMA（busy_timeout /
 * synchronous）——SQLite 的这两项不随库文件持久化。</p>
 *
 * <p>参数取值与理由（0.5.0 统一存储模型）：WAL 让读写互不阻塞；
 * busy_timeout=5000 让多线程写冲突时排队而非报错（SQLite 为单写者模型）；
 * synchronous=NORMAL 在 WAL 下进程崩溃不丢已提交事务，仅主机掉电可能丢最后
 * 几毫秒——对消息系统可接受（上游可重发），换取数量级的写入吞吐。</p>
 *
 * <p>线程安全：本类不可变，{@link #dataSource()} 返回的包装同样线程安全。</p>
 *
 * @author lianghang
 */
public final class EmbeddedSqliteDatabase implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedSqliteDatabase.class);

    /** 写冲突排队时长（毫秒）：远大于单次写事务耗时，正常不会触发超时。 */
    private static final int BUSY_TIMEOUT_MS = 5000;

    private final Path dbFile;
    private final SQLiteDataSource raw;

    /**
     * 打开（或创建）内嵌 SQLite 库。
     *
     * @param dbFile 数据库文件路径（父目录不存在时自动创建）
     */
    public EmbeddedSqliteDatabase(Path dbFile) {
        this.dbFile = dbFile.toAbsolutePath();
        try {
            Path parent = this.dbFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法创建内嵌数据库目录: " + this.dbFile, e);
        }
        this.raw = new SQLiteDataSource();
        this.raw.setUrl("jdbc:sqlite:" + this.dbFile);
        initJournalMode();
        log.info("内嵌 SQLite 已就绪: {}", this.dbFile);
    }

    /** 便捷构造：接受字符串路径（如 {@code ./data/linkora-server.db}）。 */
    public static EmbeddedSqliteDatabase atFile(String dbPath) {
        return new EmbeddedSqliteDatabase(Paths.get(dbPath));
    }

    /** 提供标准 {@link DataSource}：每次取出的连接已设置逐连接 PRAGMA。 */
    public DataSource dataSource() {
        return new PragmaticDataSource();
    }

    /** 库文件绝对路径（供启动日志/文档输出）。 */
    public Path dbFile() {
        return dbFile;
    }

    /** 关闭：SQLite 无常驻资源，仅做 WAL checkpoint 收尾，尽力而为不抛错。 */
    @Override
    public void close() {
        try (Connection connection = raw.getConnection();
             Statement st = connection.createStatement()) {
            st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.info("内嵌 SQLite 已关闭（WAL 已收敛）: {}", dbFile);
        } catch (SQLException e) {
            log.debug("内嵌 SQLite 收尾 checkpoint 跳过: {}", e.getMessage());
        }
    }

    /** journal_mode=WAL 持久化在库文件中：库级一次性设置。 */
    private void initJournalMode() {
        try (Connection connection = raw.getConnection();
             Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode = WAL")) {
                rs.next();
                log.debug("SQLite journal_mode = {}", rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("内嵌 SQLite 初始化失败: " + dbFile, e);
        }
    }

    /**
     * 逐连接 PRAGMA 包装：busy_timeout / synchronous 在每个连接取出时设置。
     * 其余 DataSource 接口方法按不可池化的直连语义实现。
     */
    private final class PragmaticDataSource implements DataSource {

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = raw.getConnection();
            try {
                tune(connection);
                return connection;
            } catch (SQLException e) {
                try {
                    connection.close();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        private void tune(Connection connection) throws SQLException {
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
                st.execute("PRAGMA synchronous = NORMAL");
            }
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("不支持 unwrap: " + iface.getName());
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            // 直连语义：无日志Writer
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLoginTimeout(int seconds) {
            // 直连语义：无登录超时
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException("不支持 java.util.logging");
        }
    }
}
