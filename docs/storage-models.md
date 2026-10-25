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

- 双 DataSource 宿主（业务库 + 自动配置的嵌入式库并存）时，
  Boot 的 `DataSourceTransactionManagerAutoConfiguration` 因多候选不生效，
  需显式声明 `PlatformTransactionManager` 并绑定 linkora 存储数据源
  （见 examples/boot-server 的 `TransactionConfig`）
- SQLite 驱动请确保 3.46+：Boot BOM 锁定的 3.36 在事务内并发写场景
  存在 `SQLITE_BUSY` 行为差异，宿主请显式声明版本
  （示例 pom 已示范）

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
