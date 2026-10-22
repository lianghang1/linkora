# 消息处理与业务接入

本文说明 Linkora 的消息处理链路：框架负责什么、业务侧在哪里接入、回调发生在哪一行。
配套可运行示例见 `examples/spring-boot-server`（starter 全自动装配）与
`examples/spring-mvc-server`（Spring MVC 宿主 + 纯 Java 客户端）。

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

| 步骤 | 代码位置 | 谁写的 |
|---|---|---|
| 1. 客户端 outbox 后台线程 POST `/messages` | `linkora-client` 发送调度 | 框架 |
| 2. 端点 Servlet 接收 → `LinkoraServer.ingestHttpHandler()` | `linkora-server-servlet` / `LinkoraServer` | 框架 |
| 3. `IngestService.ingest()`：认证 → 校验 → 持久化 | `linkora-server` `IngestService` | 框架 |
| 4. **持久化成功后回调 `IngestListener.onIngested`** | `IngestService.notifyListener()` | 框架调用、业务实现 |
| 5. 业务转发器按 messageType 路由 | 示例 `UplinkMessageDispatcher` | 业务侧 |
| 6. `UplinkHandler.handle()` 执行业务 | 示例 `OrderStatusHandler` / `ReviewReplyHandler` | 业务侧 |

要点：

- 第 4 步是**观察者回调**，业务代码里不存在显式调用点，属正常设计。
- 回调在摄入线程上**同步执行**：异常会被框架捕获并记 WARN（不影响上行返回
  200），但耗时业务应自行异步化（转线程池/MQ）。
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
- 客户端在 `MessageListener.onMessage` 里处理消息后**必须显式 ACK**：
  成功调 `message.ackProcessed()`；业务失败调 `message.ackFailed(错误码, 原因)`，
  服务端按策略重试。未 ACK 的消息会在重连/重启后补投。
- 客户端可用 `client.addListener(topic, listener)` 注册多个监听器，
  `start()` 之后仍可继续注册。

## 业务接入清单

### 服务端（上行处理）

| 宿主 | 接入方式 |
|---|---|
| Spring Boot | 写一个实现 `IngestListener` 的 `@Service`，starter 自动接入；多消息类型推荐示例的 `UplinkHandler` + `UplinkMessageDispatcher` 路由模式 |
| Spring MVC | 同上，`linkora-spring` 的 `LinkoraServerConfiguration` 自动接入 |
| 纯 Java | `LinkoraServer.builder().ingestListener(listener)` 手动注册 |

### 客户端（下行处理 + 上行发送）

- 下行：实现 `MessageListener`，按 topic `addListener` 注册。
- 上行：`client.publish(topic, payload)` 写入 SQLite outbox（进程崩溃不丢），
  后台线程自动 POST；用 outbox 状态 `SERVER_ACCEPTED` 确认服务端已接受。

## 示例索引

| 示例 | 说明 |
|---|---|
| `examples/spring-boot-server` | 线上平台 ⇄ 线下门店四向闭环，starter 全自动装配 + controller/service 分层 |
| `examples/spring-mvc-server` | 同一业务场景：Spring MVC 宿主 + 纯 Java 客户端（零 Spring） |
| `examples/plain-java-server` / `plain-java-client` | 零框架宿主的最小演示 |

服务端配置 `IngestListener` 的纯 Java 写法见 [deployment.md](deployment.md)；
线路协议与消息结构见 [protocol.md](protocol.md)；投递状态机见 [reliability.md](reliability.md)。
