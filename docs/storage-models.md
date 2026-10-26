# 存储模式（0.5.0）

Linkora 0.5.0 起，**服务端与客户端存储模型完全对等**：引擎统一 SQLite，
两种模式按需选择——

| 模式 | 语义 | 适用 |
|---|---|---|
| `sqlite`（**默认**） | 内嵌 SQLite 文件，零配置起步 | 开发 / 演示 / 小规模部署 |
| `datasource` | 复用宿主 DataSource，业务与消息**同库同事务** | 生产推荐 |

> 定位说明：Linkora 仅支持 Spring MVC（Tomcat）与 Spring Boot（内嵌 Tomcat）宿主。

## 配置（两端对称）

```yaml
linkora:
  server:
    store: sqlite            # sqlite | datasource
    db-path: ./linkora/linkora-server.db   # 仅 sqlite 模式
  client:
    store: sqlite            # sqlite | datasource
    db-path: ./linkora/linkora-client.db   # 仅 sqlite 模式
```

- `store=datasource` 要求宿主存在 DataSource Bean；缺省时启动快速失败并给出指引
- client-only 宿主（只收发消息不承载服务端）请显式 `linkora.server.enabled=false`
  （0.5.0 起服务端默认 sqlite、不再依赖宿主 DataSource 的存在性）

## 事务语义（datasource 模式）

连接统一经事务感知代理（`TxAwareDataSources.wrap`）获取：
宿主 `@Transactional` 事务内的 `server.publish(...)` / `client.publish(...)`
与业务写库**原子提交 / 原子回滚**——业务回滚时消息"从未存在"
（transactional outbox 模式，见 examples/boot-server、boot-client 的事务演示端点）。

注意事项：

- **纯 Spring MVC 宿主必须显式 `@EnableTransactionManagement`**（Boot 有自动配置，
  纯 Spring 没有——缺失时 `@Transactional` **静默失效**：无代理、无回滚、无报错，
  实测于 MySQL 真库发现，见 examples/mvc-server 的 AppConfig）
- 双 DataSource 宿主（业务库 + 自动配置的嵌入式库并存）时，
  Boot 的 `DataSourceTransactionManagerAutoConfiguration` 因多候选不生效，
  需显式声明 `PlatformTransactionManager` 并绑定 linkora 存储数据源
  （见 examples/boot-server 的 `TransactionConfig`）
- SQLite 驱动请确保 3.46+：Boot BOM 锁定的 3.36 在事务内并发写场景
  存在 `SQLITE_BUSY` 行为差异，宿主请显式声明版本
  （示例 pom 已示范）
- H2 已从项目依赖与测试基座中移除（0.5.0）：测试基座统一 SQLite 文件库
  （与内嵌默认同引擎，测试即生产行为）；datasource 模式支持的宿主业务库
  以实测为准——SQLite（示例默认）与 MySQL 8.0（真库验证）

### SQLite 驱动版本要求（3.46+，重要）

**来龙去脉**：linkora 父 pom 锁定 `sqlite-jdbc 3.46.1.3`，但宿主项目
（如 mvc 示例这类独立 pom）引入 `spring-boot-dependencies` BOM 后，
BOM 对 sqlite-jdbc 的版本管理（3.36.0.3）**优先级高于传递依赖**，
实际解析到 3.36。

**实测差异**（3.36 vs 3.46，同一场景：事务内业务写 + publish 写 +
后台 sweep 线程周期写，全部经 TADSP 事务感知包装）：

| 版本 | 行为 |
|---|---|
| 3.36.0.3 | 事务线程与后台线程的写锁竞争触发 `SQLITE_BUSY: database is locked`，busy_timeout=5000 未能如预期排队等待 |
| 3.46.1.3 | 同场景正常，busy_timeout 排队生效，无 BUSY |

（差异机理在驱动的锁等待实现层，未深挖驱动源码；结论以实测为准。）

**处理**：宿主 pom 显式声明覆盖——

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.46.1.3</version>
</dependency>
```

已修复：`examples/boot-server`、`examples/mvc-server`、`examples/mvc-client`
的 pom 均已显式锁定 3.46.1.3；仓库内模块（core/server/client/starter）
由父 pom 统一管理不受影响。

## 内嵌模式参数

`EmbeddedSqliteDatabase` 统一初始化（两端共用）：
`journal_mode=WAL`（读写不互斥）、`busy_timeout=5000`（写冲突排队）、
`synchronous=NORMAL`（进程崩溃不丢已提交事务）。负载量级参考：
日 500 条消息时写占用率不足 1%（能力 ~10,000 写/秒，每条消息约 5 次写）。

## 幂等实现

客户端 `JdbcClientStore` 的幂等写入采用"INSERT + 唯一键冲突捕获"
（`JdbcErrors.isIntegrityViolation` 兼容 H2/MySQL/SQLite 的异常映射差异），
不依赖任何方言特性——H2 常规模式不支持 `ON CONFLICT` / `INSERT IGNORE`，
实测验证后放弃方言特化路线。

## 升级到 0.5.0（breaking change）

- 服务端默认存储从"宿主 DataSource"变为"内嵌 SQLite"。已有部署升级后
  若不加配置，消息库将切到新的 SQLite 文件（历史数据仍在原库但不被读取）。
  保留原行为：加一行 `linkora.server.store=datasource`
- 历史数据迁移不提供自动工具，可用 SQL 手工搬迁（lk_* 表结构一致）
