# Linkora

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lianghang1/linkora-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.lianghang1/linkora-spring-boot-starter)

面向线上服务与多个线下 Java 客户端的可靠双端通信 SDK。

- 最低 JDK：8；宿主形态：**Spring Boot 2.x 或 Spring MVC 5.x**
- 通信方案：SSE 下行推送 + HTTPS POST 上行
- 可靠性：服务端持久化 + 客户端本地队列 + 两级 ACK + 幂等 + 重试 + 断线补发
- 投递语义：至少一次（at-least-once），业务侧需自行保证副作用幂等
- 安全：纯应用层 HMAC 签名认证（不依赖证书/mTLS），失败指数锁定 + 防重放

## 快速开始

每个角色只引 1 个 Maven 包，存储实现随包传递，无需单独声明：

| 你的宿主 | 服务端引 | 客户端引 |
|---|---|---|
| Spring Boot 应用 | `linkora-server-spring-boot-starter` | `linkora-client-spring-boot-starter` |
| Spring MVC 应用（无 Boot） | `linkora-server-spring` | `linkora-client-spring` |

> 0.6.0 起按角色拆包（官方 starter 命名规范），0.5.0 及以前的统一包已废弃，
> 迁移对照见 [docs/migration-0.6.0.md](docs/migration-0.6.0.md)。

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId> <!-- 或 linkora-spring -->
    <version>x.y.z</version>
</dependency>
```

服务端最小配置（application.yml，首次启动自动建表）：

```yaml
linkora:
  server:
    register-clients:
      store-001: true
```

客户端最小配置：

```yaml
linkora:
  client:
    enabled: true
    server-url: http://127.0.0.1:8082
    client-id: store-001
```

下发与接收（Boot 宿主，MVC 宿主装配方式不同、业务代码相同）：

```java
// 下发：注入 LinkoraServer
linkora.publish(clientId, "order-created", payload);

// 接收：实现 MessageListener 并注册（正常返回自动确认，抛异常自动进重试）

// 上行：注入 UplinkPublisher，写本地队列后自动发送与重试
uplinkPublisher.publish("order-status-updated", payload);
```

完整分步说明见 [docs/getting-started.md](docs/getting-started.md)；
**全部配置项**见 [docs/configuration.md](docs/configuration.md)。

## 传输加密与认证（0.6.0）

- **传输加密默认开启**：payload 自动 AES-256-GCM（两端配置相同密钥
  `linkora.payload-cipher-key`，缺失启动即失败；与认证无关，匿名部署可用）；
  大 payload 自动 gzip
- **三种认证模式**：HMAC 签名（默认）、Bearer Token（服务端回调宿主账号体系
  校验——已有登录系统零改造接入）、匿名；客户端 token 支持静态配置或
  TokenProvider SPI 动态获取。详见 docs/security.md 与 docs/migration-0.6.0.md
- **数据同步工具**：`com.linkora.client.sync.DataSync`——业务系统不改造时，
  旁挂客户端只读源库、快照 diff、只发增量

## 核心能力

| 能力 | 入口 | 说明 |
|---|---|---|
| 可靠投递 | 自动 | 先落库再推送、断线补发、指数退避重试、死信兜底，无需业务代码参与 |
| 顺序投递 | `publish(clientId, type, payload, orderId)` | 同键消息严格按发布顺序投递与处理；key 为空则与普通消息无异。详见 [docs/reliability.md](docs/reliability.md) |
| 带过期与链路追踪的发布 | `publish(clientId, type, payload, expiresAt, traceId)` | 到期未送达自动作废；traceId 全链路透传 |
| 慢客户端隔离 | 自动 | 非阻塞推送 + 每连接有界队列，单个失联客户端不影响其他客户端。详见 [docs/reliability.md](docs/reliability.md) |
| 消息状态查询 | `getMessageStatus` / `getBacklog` | 投递记录、积压与在线状态，可用于对账与监控 |
| 管理接口 | `LinkoraServer` 管理方法 | 监控、死信重放/跳过、踢连接、运行时启停、远程指令——全部人类可读视图。详见 [docs/api.md](docs/api.md) |
| 运维控制台 | `linkora-admin-console`（可选 jar） | 嵌入宿主容器的开箱运维界面：总览、死信处置、客户端管理、远程指令；Bearer Token 鉴权、默认关闭。详见 [docs/admin-console.md](docs/admin-console.md) |
| 自定义存储 | 存储 SPI / 事务接管 | 服务端复用宿主 DataSource 或业务库事务（发件箱语义）；客户端队列可进 MySQL。详见 [docs/custom-storage.md](docs/custom-storage.md) |

## 使用前必读

1. **投递语义是至少一次**：同一条消息可能被重复送达，业务处理必须幂等（用消息 ID 去重）。
2. **业务处理失败要主动上报**：监听器正常返回即自动确认成功（无需调用 ACK）；
   失败时抛出异常或显式调用 `message.ackFailed(code, msg)`，服务端将按策略重试。
3. **生产必须配 HMAC 认证**：不提供 `ClientAuthenticator` 时服务端直接启动失败。
   本地开发需显式设置 `linkora.server.allow-anonymous=true`（启动日志留痕），
   该开关不应出现在生产配置。接入步骤见 [docs/security.md](docs/security.md)。

更多接入约束（JDK/Spring 版本、sqlite-jdbc 版本唯一性、依赖体积）见
[docs/getting-started.md](docs/getting-started.md) 的"版本与依赖约束"一节。

## 文档索引

| 文档 | 内容 | 适合谁 |
|---|---|---|
| [docs/getting-started.md](docs/getting-started.md) | Boot / MVC 宿主分步接入、全部配置项、版本与依赖约束 | 第一次接入 |
| [docs/integration-guide.md](docs/integration-guide.md) | 业务接入清单：认证、clientId 规划、顺序键、过期与 payload 规范、幂等 | 接入前通读 |
| [docs/api.md](docs/api.md) | 服务端与客户端管理 API 参考：方法签名、入参、返回字段、状态对照表 | 运维与监控开发 |
| [docs/reliability.md](docs/reliability.md) | 可靠性语义：投递状态机、重试与死信、顺序保证、推送写模型 | 所有使用者 |
| [docs/message-handling.md](docs/message-handling.md) | 消息处理细节：监听器、ACK、上行生命周期 | 业务开发 |
| [docs/security.md](docs/security.md) | HMAC 认证、凭据管理、防重放 | 接入与安全 |
| [docs/custom-storage.md](docs/custom-storage.md) | 自定义存储与事务接管（发件箱语义）、客户端 MySQL 队列 | 存储改造 |
| [docs/storage-spi.md](docs/storage-spi.md) | 存储 SPI 完整契约 | 自实现存储 |
| [docs/deployment.md](docs/deployment.md) | 部署形态、数据库表结构、容量与维护 | 运维 |
| [docs/admin-console.md](docs/admin-console.md) | 嵌入式运维控制台：开启方式、页面与端点、远程指令通道 | 运维 |
| [docs/architecture.md](docs/architecture.md) | 模块划分与内部结构 | 深入了解 |
| [docs/protocol.md](docs/protocol.md) | 线路协议：消息 JSON、SSE 事件、HTTP 端点 | 深入了解 |
| [docs/compatibility.md](docs/compatibility.md) | 容器/JDK/Spring 兼容矩阵 | 升级评估 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见问题与排查 | 出问题时 |
| [docs/design/](docs/design/) | 关键设计决策存档 | 维护者 |

## 可运行示例

`examples/` 下每个场景都有开箱即跑的工程（Boot / MVC 双端、事务接管），
工程结构即推荐接入结构：

```bash
mvn install -DskipTests

# Spring Boot：终端 1 起服务端（8082），终端 2 起客户端（自动跑四向闭环输出 [PASS]）
mvn -pl examples/boot-server spring-boot:run
mvn -pl examples/boot-client spring-boot:run

# Spring MVC：终端 1 起服务端（8081），终端 2 起客户端
mvn -pl examples/mvc-server compile exec:java "-Dexec.mainClass=com.linkora.example.springmvc.SpringMvcServerMain" "-Dexec.args=8081"
mvn -pl examples/mvc-client compile exec:java "-Dexec.mainClass=com.linkora.example.mvcclient.ClientDemoApplication"
```

服务端启动后，管理接口演示端点可直接体验（生产必须自行加鉴权）：

```bash
GET  http://127.0.0.1:8082/api/admin/clients          # 客户端概览
GET  http://127.0.0.1:8082/api/admin/unfinished?clientId=demo-client
GET  http://127.0.0.1:8082/api/admin/dead-letters?clientId=demo-client
GET  http://127.0.0.1:8082/api/admin/messages?clientId=demo-client
GET  http://127.0.0.1:8082/api/admin/stats?days=7
POST http://127.0.0.1:8082/api/admin/disconnect?clientId=demo-client
POST http://127.0.0.1:8082/api/admin/skip?clientId=demo-client&messageId=...
```

## 构建

```bash
mvn clean install          # 全模块构建 + 测试
mvn clean verify           # 含集成测试
```

## License

见 [LICENSE](LICENSE)。
