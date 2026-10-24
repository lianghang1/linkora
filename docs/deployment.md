# 部署指南

## 服务端部署（嵌入宿主容器，零新增中间件）

Linkora 服务端不是一个独立进程，而是一组可嵌入已有 HTTP 服务的组件。
生产部署只需三步：

### 1. 注册三个 Servlet（Servlet 3.1+ 容器）

```java
LinkoraServer server = LinkoraServer.builder()
        .clientAuthenticator(new HmacClientAuthenticator(credentialStore)) // 见 security.md
        .clientRegistry(new JdbcClientRegistry(dataSource))
        .messageRepository(new JdbcMessageRepository(dataSource))
        .deliveryRepository(new JdbcDeliveryRepository(dataSource))
        .cursorRepository(new JdbcClientCursorRepository(dataSource))
        .config(ServerConfig.builder().build())
        // 可选：上行消息业务监听器，客户端上报持久化成功后被回调（Spring 宿主
        // 注册 IngestListener bean 即自动接入，见示例 service/uplink 包）
        .ingestListener(message -> handleUplink(message))
        .build();
server.start();

// contextPath 任意，客户端通过 serverUrl 前缀对齐
context.addServlet(new ServletHolder(
        new SsePushServlet(server.sseSubscribeHandler(), server.sseConnectionRegistry())), "/events");
context.addServlet(new ServletHolder(
        new LinkoraEndpointServlet(server.ingestHttpHandler())), "/messages");
context.addServlet(new ServletHolder(
        new LinkoraEndpointServlet(server.ackHttpHandler())), "/acks");
```

客户端 `serverUrl` = `http(s)://host[:port]/<contextPath前缀>`，默认子路径
`/events`、`/messages`、`/acks`（可用 `ClientConfig` 的
`eventsPath/ingestPath/ackPath` 改）。

### 2. 数据库（复用现有业务库）

- 首次启动调用一次 `JdbcServerSchema.migrate(dataSource)`；
- 迁移为自研版本机制（`lk_schema_version` 表 + 顺序脚本），每个脚本独立事务，
  **任意时刻崩溃重启后自动续跑**，重复调用幂等；
- 表前缀 `lk_*`（lk_client、lk_client_seq、lk_client_cursor、lk_message、
  lk_delivery），不触碰业务表；
- 已在 H2 2.2 与 MySQL 8.0 真库完成验证（迁移幂等、并发投递序号分配、
  事务接管，见 `JdbcServerStorageMySqlIT`）；PostgreSQL 接入前请先在测试库
  跑一次 `migrate`（见 compatibility.md）。

### 3. 发放客户端凭据（启用 HMAC 时）

1. `HmacSha256.generateToken()` 生成 256 bit 访问令牌；
2. `HmacSha256.generateToken()` 同样可生成 secret（或自备 ≥32 字节随机值）；
3. 宿主将 `clientId ↔ token ↔ secret` 写入自有凭据表，`CredentialStore`
   实现按 clientId 查询即可；
4. 轮换：CredentialStore 同时接受新旧 token/secret（双凭据并存窗口），
   SDK 侧无需变更。

## 服务端配置参考（ServerConfig）

| 参数 | 默认值 | 说明 |
|---|---|---|
| `allowAnonymous` | false | 缺认证器时是否允许匿名放行；默认拒绝启动 |
| `sweepIntervalMs` | 5000 | 补发/重推扫描周期（≥100） |
| `pushBatchSize` | 100 | 单次扫描每客户端最多处理的投递记录数 |
| `maxDeliveryRetries` | 10 | FAILED ACK 累计次数上限，超过进入死信 |
| `retryBackoffBaseMs/MaxMs` | 2000 / 300000 | 失败重试指数退避 |
| `pushPacingBaseMs/MaxMs` | 5000 / 60000 | 在线客户端重推节奏（防风暴） |
| `messageSizeLimitBytes` | 1MB | 上行 **HTTP body** 上限。原始 payload ≈ 该值 × 0.75（Base64 膨胀 4/3 + 信封开销），默认约 786KB |
| `maxOutboundBytesPerConnection` | 8MB | 单连接 SSE 推送队列字节水位，超限主动断开慢客户端（见 reliability.md"推送写模型"） |

## 客户端部署

- 引入 `linkora-client`（自带 OkHttp 传输与 SQLite 本地存储）；
- SQLite 库文件位置：`new SqliteClientStore(Path)`（父目录自动创建）；
  **每个客户端实例独占一个库文件**，多实例共享同一文件会被 WAL 锁竞争拖慢；
- 磁盘要求：消息积压上限 ≈ 未送达消息量，按业务峰值估算；
  已完成记录默认保留 7 天后自动清理；
- 客户端参数见 [ClientConfig](../linkora-client/src/main/java/com/linkora/client/ClientConfig.java)
  javadoc（超时、批次、重试、保留期均有界校验，默认值适合多数场景）。

## 优雅关闭

关闭顺序：先停 HTTP 容器（停止接新请求）→ `server.close()` /
`client.close()`。两个 close 均幂等：

- 服务端：停止扫描与推送调度，在途状态已全部持久化；
- 客户端：停止 SSE/上报调度，outbox/inbox 状态已全部持久化，
  **进程被 kill -9 也安全**，重启后自动恢复续跑。

## 健康检查与观测

- `server.isRunning()`：存活探测；
- `server.getBacklog(clientId)`：某客户端积压量；
- `server.getMessageStatus(messageId)`：单条消息投递状态与全部投递记录；
- `server.sweepOnce()`：手动触发一次补发扫描（运维补投后立即可用）；
- 日志：SLF4J 门面，实现交给宿主；日志含 messageId/clientId/traceId，
  不含凭据与未脱敏负载。

## 升级与备份

- **升级**：新版本若含 schema 变更，启动时自动增量应用；
  先在测试库验证，再对生产库做常规备份后升级；
- **备份**：按普通业务表备份 `lk_*` 六张表；客户端侧备份 SQLite
  `.db`（及 WAL）文件；恢复后各端按持久化状态自动续跑，无需人工对账；
- **死信处置**：通过管理接口查询死信并人工重放（`retryDeadLetter`）或跳过
  （`skipMessage`），方法与边界见 [api.md](api.md)。

## 数据库表

服务端在业务库里建 6 张 `lk_` 前缀表。首次启动自动建表（全部 `IF NOT EXISTS`
幂等迁移），不触碰业务表；linkora 表与业务表同库共存，互不干扰。

| 表 | 用途 |
|---|---|
| `lk_message` | 下行消息正文：messageId、类型、payload（base64）、顺序键、过期时间、traceId |
| `lk_delivery` | 每条消息对每个客户端的投递状态：状态机、投递序号、顺序键、重试次数、下次重试时间、最后错误——可靠性核心表 |
| `lk_client` | 客户端注册与在线状态：启用/禁用、SSE 连接状态、最后活跃时间 |
| `lk_client_seq` | 每客户端投递序号分配（行锁串行化，保证并发下无重复、无跳号） |
| `lk_client_cursor` | 客户端 ACK 游标（断线重连续传的依据） |
| `lk_schema_version` | SDK 表结构版本号（自动迁移用） |

客户端 SQLite 单文件（`db-path` 指定，缺省路径自动创建），4 张表：

| 表 | 用途 |
|---|---|
| `lk_outbox` | 上行发件队列：先落库再发送，含状态/重试计数/下次重试时间，崩溃后自动补传 |
| `lk_inbox` | 下行收件队列：先落库再回调业务，接收/处理两级状态，去重与 ACK 补发的依据 |
| `lk_meta` | SDK 元数据（本地游标等） |
| `lk_schema_version` | 客户端本地结构版本号 |

payload 存储与大小上限：payload 列按 LONGTEXT/CLOB 建表（迁移自动完成），
存储不是瓶颈；真正的上限是 `message-size-limit-bytes`（HTTP body，默认 1MB，
对应原始 payload 约 786KB）。

升级与维护：结构变更由 SDK 自动顺序迁移（按 `lk_schema_version` 逐版本执行，
中途崩溃重启自动续跑），宿主无需手动执行任何 SQL。所有时间字段统一存 epoch
毫秒（整数），跨时区、跨数据库一致。客户端替换为 MySQL 时建表语句见
[custom-storage.md](custom-storage.md)。
