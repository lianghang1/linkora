# 消息处理与业务接入

本文说明 Linkora 的消息处理链路：框架负责什么、业务侧在哪里接入、回调发生在哪一行。
配套可运行示例见 `examples/boot-server、boot-client`（starter 全自动装配）与
`examples/mvc-server、mvc-client`（Spring MVC 宿主服务端 + 客户端）。

## 端点归属

三个 HTTP 端点全部由框架提供（`LinkoraServlets` 统一注册 / starter 自动注册），
业务 Controller 不注册这些路径、不解析这些报文：

| 端点 | 方法 | 归属 | 作用 |
|---|---|---|---|
| `/messages` | POST | 框架 | 客户端上报消息（上行） |
| `/acks` | POST | 框架 | 客户端 ACK 回传（下行投递确认） |
| `/events` | GET(SSE) | 框架 | 服务端下行推送通道 |

业务侧与框架只有一个约定：**实现监听器接口 + 注册**。消息收发、持久化、
重试、补投全在框架内部完成。

## 上行调用链（客户端 → 服务端）

以示例的"线下回传订单状态"（topic `order-status-updated`）为例：

```
线下客户端              框架(端点 Servlet)        IngestService            业务 Handler
    │  POST /messages         │                       │                        │
    │ ───────────────────────►│      ingest(msg)      │                        │
    │                         │ ─────────────────────►│ 认证 → 校验 → 持久化    │
    │                         │                       │ ── onIngested 回调 ───►│ 按类型路由 handle()
    │ ◄───── 200 ACCEPTED ──────────────────────────── │                        │
```

| 步骤 | 代码位置 | 实现方 |
|---|---|---|
| 1. 客户端 outbox 后台线程 POST `/messages` | `linkora-client` 发送调度 | 框架 |
| 2. 端点 Servlet 接收 → `LinkoraServer.ingestHttpHandler()` | `linkora-server`（servlet 包）/ `LinkoraServer` | 框架 |
| 3. `IngestService.ingest()`：认证 → 校验 → 持久化 | `linkora-server` `IngestService` | 框架 |
| 4. **持久化成功后回调 `IngestListener.onIngested`** | `IngestService.notifyListeners()` | 框架调用、业务实现 |
| 5. 内置路由器按 messageType 路由 | SDK `com.linkora.server.uplink.UplinkRouter`（Spring 宿主自动接入） | 框架 |
| 6. `UplinkHandler.handle()` 执行业务 | 示例 `OrderStatusHandler` / `ReviewReplyHandler`（实现 SDK 的 `com.linkora.core.uplink.UplinkHandler`） | 业务侧 |

要点：

- 第 4 步是**观察者回调**，业务代码里不存在显式调用点，属正常设计。
- 服务端可注册**多个** `IngestListener`（`builder().ingestListener(...)` 可链式
  累加，Spring 容器内多个 bean 全部接入），按注册顺序逐个回调。
- 回调在摄入线程上**同步执行**：异常会被框架捕获并记 WARN（不影响上行返回
  200，也不影响后续监听器），但耗时业务应自行异步化（转线程池/MQ）。
- 回调发生在持久化之后，因此即使业务处理失败，消息也不会丢——业务可自行
  重试或落库补偿。

## 下行调用链（服务端 → 客户端）

```
业务 Service            LinkoraServer             线下客户端框架            业务 Handler
    │  publish(topic,msg)      │                       │                        │
    │ ────────────────────────►│ 持久化 + 路由          │                        │
    │                          │ ── SSE /events 推送 ──►│                        │
    │                          │                       │ ── onMessage 回调 ────►│ 解析·处理
    │                          │ ◄─ POST /acks ─────── │ ackProcessed() 后自动回 │
```

要点：

- `publish()` 先持久化：客户端离线时等上线补投，在线时经 SSE 实时送达。
- 监听器处理完成后的确认是**自动的**：所有监听器正常返回且未显式 ACK 时，
  SDK 自动标记处理成功（幂等，重复调用无效）。
- 业务失败两种上报方式：抛出 RuntimeException，或显式调用
  `message.ackFailed(错误码, 原因)`——服务端均按策略重试，重连/重启后补投。
- **解码失败不静默丢弃**：payload 损坏、JSON 不完整等导致 `decodeMessage` 失败时，
  客户端会回一条 `FAILED` ACK（错误码 `INVALID_MESSAGE`），由服务端按重试策略计数，
  达到 `maxDeliveryRetries`（默认 10）后进死信。否则服务端会按 pacing 无限重推。
  连 `messageId` 都提取不到时无法回 ACK，此时只能靠消息的 `expiresAt` 兜底。
- 客户端可用 `client.addListener(topic, listener)` 注册多个监听器，
  `start()` 之后仍可继续注册。

## 业务接入清单

> 完整上线检查清单见 [integration-guide.md](integration-guide.md)。

### 服务端（上行处理）

| 宿主 | 接入方式 |
|---|---|
| Spring Boot | 上行：写一个继承 SDK `JsonUplinkHandler<T>`（payload 自动 JSON 反序列化）或实现 `UplinkHandler` 的 `@Service`，starter 自动收集并经内置 `UplinkRouter` 分发（零装配代码）；需要泛化回调时也可实现 `IngestListener` bean |
| Spring MVC | 同上，`linkora-spring` 的 `LinkoraServerConfiguration` 自动接入 |
| 纯 Java | `LinkoraServer.builder().ingestListener(listener)` 手动注册 |

### 客户端（下行处理 + 上行发送）

- 下行：实现 `MessageListener`，按 topic `addListener` 注册。
- 上行：`client.publish(topic, payload)` 写入 SQLite outbox（进程崩溃不丢），
  后台线程自动 POST；用 `client.addOutboxListener(...)` 监听 outbox 状态变更，
  收到 `SERVER_ACCEPTED` 回调即确认服务端已接受（替代轮询本地队列）。
- 需要顺序时：`client.publish(topic, payload, sequenceKey)`——同键消息严格按发布顺序
  被服务端接收（前一条未被确认前，后一条不会发出）；下行用服务端带 key 的 publish
  重载。键的取法与边界见 [docs/reliability.md](reliability.md) 的"顺序保证"章节。

## payload 格式约定

框架不约束 payload 内容（`byte[]` 透传）。示例统一使用 JSON（UTF-8），
如 `{"orderNo":"SO-1001","status":"SHIPPED"}`——字段演进无需改拆分逻辑，
也便于跨语言。生产环境可用 Jackson 自定义 DTO 序列化，或实现框架
`PayloadSerializer` 走对象负载。

## 示例索引

| 示例 | 说明 |
|---|---|
| `examples/boot-server、boot-client` | 线上平台 ⇄ 线下门店四向闭环，starter 全自动装配 + controller/service 分层 |
| `examples/mvc-server、mvc-client` | 同一业务场景：Spring MVC 宿主（服务端 + 客户端均引 linkora-spring 单包装配） |
| `examples/plain-java-server` / `plain-java-client` | 同一业务场景：零框架宿主，`ingestListener` 手动接入演示 |

服务端配置 `IngestListener` 的纯 Java 写法见 [deployment.md](deployment.md)；
线路协议与消息结构见 [protocol.md](protocol.md)；投递状态机见 [reliability.md](reliability.md)。
