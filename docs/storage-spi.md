# 存储层 SPI 契约

Linkora 把持久化抽象为两端独立的 SPI，上层编排（linkora-server / linkora-client）只依赖接口，
不绑定任何具体数据库。本文是自实现存储（含"宿主事务接管"模式）的权威契约。

## 1. SPI 总览

| 端 | 接口 | 模块 | 默认实现 |
|---|---|---|---|
| 服务端 | `MessageRepository` | linkora-core | `JdbcMessageRepository`（linkora-server-storage-jdbc） |
| 服务端 | `DeliveryRepository` | linkora-core | `JdbcDeliveryRepository` |
| 服务端 | `ClientRegistry` | linkora-core | `JdbcClientRegistry` |
| 服务端 | `ClientCursorRepository` | linkora-core | `JdbcClientCursorRepository` |
| 服务端 | schema 迁移 | — | `JdbcServerSchema.migrate(DataSource)`（linkora-server-storage-jdbc） |
| 客户端 | `ClientStore` | linkora-core | `SqliteClientStore`（linkora-client-storage-sqlite） |

关联数据类：服务端 `DeliveryRecord` / `ClientRegistration`；客户端 `OutboxEntry` / `InboxEntry`。

## 2. 通用契约（两端都必须满足）

1. **幂等去重**：所有以 messageId 为主键的写入必须由数据库唯一约束兜底，
   重复写入返回"未写入"（false / 忽略 / 影响行数 0），不得覆盖已有记录、不得抛错。
   这是超时重试、崩溃恢复等场景下"至少一次投递不重复"的最终防线。
2. **CAS 状态更新**：所有状态迁移采用 compare-and-set 语义——SQL 层面即
   `UPDATE ... SET 状态=新 WHERE ... AND 状态=期望`，影响行数 0 表示竞争失败。
   旧请求（迟到 ACK、重复回调）**不得回退**已完成状态。
3. **线程安全**：编排层会从多个线程并发调用（服务端调度线程 + HTTP 工作线程；
   客户端发送线程 + SSE 接收线程 + 宿主业务线程），实现必须可并发使用。
4. **异常语义**：实现内部失败统一抛 `LinkoraException(STORAGE_ERROR, ...)`，
   上层只按此类型归类处理，不吞异常。
5. **时间戳**：全部毫秒级 epoch（`System.currentTimeMillis()`），BIGINT 存储。
6. **负载存储**：字节数组负载统一 Base64 TEXT 或原生 BLOB 存储；JDBC 参考实现
   选择 Base64 TEXT，规避 BLOB/BYTEA 在 MySQL/PostgreSQL/H2 间的类型分歧。

## 3. 服务端 SPI 契约

### 3.1 表结构（JdbcServerSchema v1）

```
lk_client          客户端注册表        (client_id PK, enabled, created_at, connection_state, last_active_at)
lk_client_seq      投递序号分配表      (client_id PK, last_seq)
lk_client_cursor   客户端投递游标      (client_id PK, last_seq)
lk_message         消息正文            (message_id PK, client_id, message_type, payload_base64,
                                       created_at, expires_at, schema_version, trace_id)
lk_delivery        投递状态            (message_id+client_id PK, seq, state, retry_count,
                                       next_retry_at, last_error, created_at, updated_at)
```

关键索引：`lk_delivery(client_id, state, seq)`（补发扫描）、
`uk_delivery(client_id, seq)`（序号唯一）、`lk_message(client_id, created_at)`。

### 3.2 迁移机制

自研轻量迁移（不引入 Flyway）：

- 版本表 `lk_schema_version(version PK, applied_at)` 记录已应用脚本；
- 每个脚本在**独立事务**中执行并记录版本号，任意时刻崩溃重启后从上次版本继续；
- `migrate()` 可重复调用（幂等），服务端启动前必须执行一次；
- SQL 保持方言中立（ANSI），已验证 H2，MySQL 8 / PostgreSQL 待三库矩阵验证。
  自实现时注意避开 `ON CONFLICT` 等 MySQL 不支持的方言（用"唯一约束 + 捕获重复键异常"替代）。

### 3.3 投递序号（seq）分配

`DeliveryRepository.nextDeliverySeq(clientId)` 是正确性核心：

- seq 按客户端独立递增（从 1 开始），SSE 断线补发与客户端去重都依赖它的严格性；
- 标准实现 = 单事务内三步：INSERT-OR-SKIP 初始化序号行 → `UPDATE last_seq = last_seq + 1`
  （行锁串行化）→ 同事务读回。并发分配无重复、无跳号；
- 不得用数据库自增列替代（自增列在事务回滚时会跳号），
  也不得用 `SELECT MAX` 后再写（并发下会产生重复 seq）。

### 3.4 客户端注册表

`register()` 幂等：重复注册保留首次记录；`enabled=false` 的客户端拒收新消息
（publish 报错）且拒绝 SSE 订阅。连接状态由服务端编排维护，实现只负责存取。

## 4. 客户端 ClientStore 契约

### 4.1 两个队列 + 元数据

| 区域 | 用途 | 状态机 |
|---|---|---|
| outbox | 上行暂存（先落盘再发送） | PENDING → SENDING → SERVER_ACCEPTED（终态）/ DEAD（超限） |
| inbox | 下行暂存（先落盘再 ACK） | 接收：PERSISTED → RECEIVED_ACK_SENT；处理：PENDING → PROCESSING → PROCESSED / FAILED |
| meta | 续传游标等本地元数据 | 纯 KV |

### 4.2 关键约束

- **查询排序**：`findOutbox/findInboxByProcessStatus` 按 `next_retry_at` 升序（NULL 视为立即到期）
  返回，LIMIT 截断——补传调度依赖该顺序保证公平与退避正确；
- **清理边界**：outbox 只删 `SERVER_ACCEPTED`；inbox 只删"接收 ACK 已发送 **且** 处理完成"的记录。
  提前删除未完成记录会导致服务端持续补发（inbox）或消息丢失（outbox）；
- **线程安全**：SQLite 参考实现用"单连接 + 内部锁串行化"简化正确性推理；
  基于连接池的宿主实现天然满足并发要求。

## 5. 事务边界与"宿主事务接管"模式

### 5.1 默认模式（内置实现）

- 服务端：`Jdbc*Repository` 每个方法独立取连接、独立提交；消息持久化与业务数据
  **不同事务**。若业务库写入成功但 publish 前崩溃，业务已生效而消息未发出——
  需要强一致的业务请使用下方接管模式；
- 客户端：`SqliteClientStore` 独立 SQLite 文件，outbox 写入与宿主业务事务天然隔离。

### 5.2 服务端接管（宿主自实现 Repository / 共享宿主连接）

让 `publish()` 的持久化与宿主业务写入同生共死（事务性发件箱）：

1. **方式一（推荐，零代码）**：把宿主 `DataSource` 包一层
   `TransactionAwareDataSourceProxy` 再喂给 `Jdbc*Repository`。效果：
   - 在宿主事务内（如 Spring `@Transactional`）调用的仓储方法自动加入当前事务，
     `commit()/rollback()/setAutoCommit()` 调用被代理忽略，由事务管理器统一裁决；
   - 编排层自身线程（重试扫描等）在事务外逐语句自动提交，行为不变。
2. **方式二**：直接实现四个 Repository SPI，从宿主事务基础设施获取绑定连接。
   自管事务的方法必须尊重连接当前 autoCommit 状态：仅 `autoCommit=true` 时
   本地提交；`false` 视为宿主事务接管，严禁中途 commit/rollback
   （`JdbcDeliveryRepository.nextDeliverySeq` 是参考实现，见第 6 节 checklist）。

接管模式语义要点（务必理解后再上线）：

- `publish()` 返回"已接受"只代表**当前事务内可见**；宿主事务回滚后消息随之消失，
  发布方不得依赖该返回值做跨事务的可靠承诺；
- seq 分配的行锁会持有到宿主事务提交/回滚为止——同一客户端的大事务会串行化
  该客户端的投递记录写入，长事务业务需评估影响；
- SSE 首推在持久化后异步进行，可能在宿主事务提交前就尝试推送
  （读到的是事务外旧数据，推送落空）；提交后扫描周期内会自动补推，不影响正确性。

### 5.3 客户端接管（宿主自实现 ClientStore）

让 outbox 写入与宿主业务同事务（客户端侧事务性发件箱）：

- 宿主实现 `ClientStore` 接入业务库（H2/MySQL 等任意 JDBC 源）；
- 在宿主事务内调用 `LinkoraClient.publish()`：`saveOutbox` 加入当前事务，
  **业务回滚 → outbox 记录随之消失**，消息从未存在；
  **业务提交 → 后台发送线程拾取并 POST**，此后进入常规可靠投递流程；
- 事务外调用（后台发送/恢复线程）逐语句提交，与宿主事务互不干扰；
- 实现方式与服务端相同：`TransactionAwareDataSourceProxy` 包装宿主连接池，
  或自实现连接绑定。

完整可运行示例：`examples/spring-tx-server`（服务端接管）、`examples/tx-client`（客户端接管）。

## 6. 自实现 checklist

- [ ] messageId 唯一约束 + 重复写入幂等（不覆盖、不报错）
- [ ] 所有状态更新 CAS（WHERE 期望状态），旧请求不可回退状态
- [ ] `nextDeliverySeq` 单事务行锁串行化（服务端）
- [ ] 自管事务的方法必须尊重连接当前 autoCommit 状态——仅 `autoCommit=true`
      时自行 begin/commit；`autoCommit=false` 视为宿主事务接管，
      **严禁中途 commit/rollback/setAutoCommit**（否则破坏事务性发件箱语义）
- [ ] 查询按 next_retry_at 升序、LIMIT 生效（客户端）
- [ ] 清理只删终态/已确认记录（outbox=SERVER_ACCEPTED；inbox=双完成）
- [ ] 多线程并发安全；异常统一 `LinkoraException(STORAGE_ERROR)`
- [ ] schema 迁移幂等、崩溃续跑（服务端）；启动前完成迁移
- [ ] SQLite 参考实现保持 WAL + synchronous=FULL；宿主库遵循宿主自身的持久化配置
