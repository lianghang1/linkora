# 存储指南（Storage）

> 0.6.0：两端存储模型完全对等。一篇讲清：怎么选、怎么配、事务语义、
> 自定义实现。合并自原 storage-models / custom-storage / storage-spi 三篇。

## 一、两种存储模式（两端对称，一个键切换）

| | `store=sqlite`（**默认**） | `store=datasource` |
|---|---|---|
| 定位 | 开发 / 演示 / 小规模部署 | **生产推荐** |
| 数据落在 | 内嵌 SQLite 文件（零外部依赖） | 宿主 DataSource（MySQL/H2 等业务库） |
| 外部依赖 | 无 | 宿主已有的数据库 |
| 同库同事务 | ——（库是 linkora 专用） | **publish 与业务 `@Transactional` 原子提交**（transactional outbox） |
| 备份 | 拷一个 `.db` 文件 | 随业务库 |

```yaml
linkora:
  server:
    store: sqlite                    # 默认；db-path 默认 ./linkora/linkora-server.db
    # store: datasource              # 生产：复用宿主 spring.datasource，无需其他配置
  client:
    store: sqlite                    # 客户端同理；db-path 默认 ./linkora/linkora-client.db
```

- 首次启动自动建表并迁移（幂等，崩溃续跑安全）
- sqlite 模式启动日志会提示"适用于开发/演示/小规模部署，生产建议 datasource"
- **容量论证参考**：SQLite 内嵌（WAL）约 2,000 条消息/秒上限；日几千~几万条
  的业务占用率不足 1%

## 二、事务语义（datasource 模式的核心价值）

业务方法上加 `@Transactional` 后，**业务表写入与消息写入要么一起提交、要么一起回滚**：

```java
@Transactional
public void placeOrder(Order order) {
    orderRepo.save(order);                    // 业务行
    linkora.publish(clientId, "order-created", payload, order.getId());  // 消息行
    // 任一步抛异常 → 两者一起回滚：消息"从未存在"
}
```

- 服务端（`server.publish`）与客户端（`client.publish`）语义完全一致
- 连接经事务感知包装自动加入宿主事务，无需额外代码
- 客户端回滚演示：业务行与 outbox 消息一起消失，服务端确认收不到

### 纯 Spring MVC 宿主注意（易踩坑）

**必须显式 `@EnableTransactionManagement`**（Boot 有自动配置，纯 Spring 没有）。
缺失时 `@Transactional` **静默失效**：无代理、无回滚、无报错。已实测踩坑并修复，
见 `examples/mvc-server` 的 AppConfig。

## 三、SQLite 驱动版本（重要）

宿主引入 `spring-boot-dependencies` BOM 会把 sqlite-jdbc 压到 **3.36.0.3**——
该版本在事务内并发写场景存在 `SQLITE_BUSY` 行为差异。宿主请显式锁定 **3.46+**：

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <version>3.46.1.3</version>
</dependency>
```

内嵌模式标准化 PRAGMA：`WAL + busy_timeout=5000 + synchronous=NORMAL`。
默认库文件位于 `./linkora/`（有归属标识，不易被误清理；`db-path` 可改）。

## 四、客户端存储细节

- 三张表：`lk_client_outbox`（上行队列）/ `lk_client_inbox`（下行处理）/ `lk_client_meta`，
  0.6.0 新增 `lk_client_snapshot`（数据同步快照）
- SQLite 模式每个实例独占一个库文件；`WAL + synchronous=FULL`（进程崩溃/断电不丢）
- datasource 模式下这些表建在宿主库中，与业务表同库同事务
- 传输加密的密文只存在于线路上，落库均为明文

## 五、自定义存储（SPI）

内置实现（SQLite / JDBC）覆盖绝大多数场景；需要接入特殊存储（如把队列落进
宿主 JPA 体系）时实现 SPI：

| SPI | 位置 | 职责 |
|---|---|---|
| 服务端四仓储 | `MessageRepository` / `DeliveryRepository` / `ClientRegistry` / `ClientCursorRepository` | 消息/投递/注册/游标 |
| 客户端 | `ClientStore`（outbox/inbox/meta/游标 + 0.6.0 快照方法） | 本地队列 |

契约要点（完整 DDL 与约束见源码 Javadoc）：

- 上行幂等：按 messageId 主键去重，重复保存返回 false 不报错
- 投递状态机：CAS 更新（`UPDATE ... WHERE state = 期望值`），禁止覆盖
- 游标单调：`last_seq` 只增不减
- 客户端快照方法为 JDK 8 default 方法（抛 Unsupported），自定义实现按需覆盖
- 事务接管：宿主自实现时连接取自事务感知的宿主数据源，即可与业务同事务
  （参考 `examples/boot-server` 的 datasource 模式与 `TransactionConfig`）

宿主自定义 Bean 会覆盖 starter 内置装配（`@ConditionalOnMissingBean` 语义）。
