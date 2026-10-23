# 设计：SSE 写线程模型改造（非阻塞写 + 每连接出站队列）

状态：**已实施**（2026-10-23，验收全过）
作者：linkora 维护者
分支（实施用）：`feat/sse-async-write`（从 `test/integration-suite` 切出）

---

## 1. 问题陈述（实测数据，非推测）

当前服务端所有推送（业务首推、周期 sweep、重连补发）由**一个编排线程串行执行**，
写客户端的动作是同步 `PrintWriter.flush()`。当客户端"假在线"（TCP 在、不读数据）时：

| 容器 | 实测结果（1MB payload × 2 灌满缓冲区后） |
|---|---|
| Jetty | 编排线程卡住 ~26s 后自愈（AsyncContext 默认 30s 异步超时意外兜底） |
| **Tomcat（Boot/MVC 常规部署）** | **kick 无效、120s 不自愈、`spring.mvc.async.request-timeout` 无效**——消息停在 ACCEPTED，唯一出路是 TCP 层放弃（15~30 分钟量级）或重启服务端 |

期间**所有其他客户端的推送、sweep、补发全部停摆**。数据零丢失（落库兜底），但实时性归零，
且无任何运营手段可快速解卡（管理接口 `disconnectClient` 实测对阻塞中的写无效——
`close()` 走 `asyncContext.complete()`，无法中断另一个线程持有中的阻塞写）。

验收基线测试：`StuckClientDisconnectTomcatIT`（本仓库，修复后该测试必须秒级自愈）。

## 2. 目标 / 非目标

**目标**
1. 隔离：任何客户端的写入问题只影响它自己的连接
2. 零数据丢失：断开/积压处置后重连补发完整恢复（复用既有 backfill + sweep）
3. 顺序不变：同一连接 SSE 帧顺序严格保持（单队列保证）
4. 协议与语义不变：帧格式、ACK、状态机、API 签名逐字节/逐签名保留

**非目标**：不做跨实例；不改协议；不动编排线程/状态机/存储；不引入第三方运行时依赖。

## 3. 方案

### 3.1 架构

```
现在：  编排线程 ── 同步 flush()（阻塞，卡全体）──→ socket
改成：  编排线程 ── 入队（微秒级）──→ 每连接有界出站队列 ──→ 容器 WriteListener 泵 ──→ socket
```

- I/O 引擎 = Servlet 3.1 标准非阻塞写（`WriteListener` / `ServletOutputStream.isReady()`），
  由 Tomcat/Jetty 内核实现——**不自己写 NIO**
- 我们写装配层（预计 200~300 行）：`LinkedBlockingQueue`（JDK 标准类）+ 官方骨架的泵 + 溢出策略

### 3.2 连接生命周期

```
GET /events → 200 + 初始帧（Servlet 线程，现状保留）
           → startAsync + setTimeout(0)   // 禁容器总超时（SSE 长连接不能被杀）
           → 创建出站队列（字节水位，默认 8MB）
           → 注册 WriteListener，请求线程返回

业务帧/心跳帧 → connection.sendEvent() = 入队（编排线程/心跳线程调用，微秒级返回）
容器回调 onWritePossible → while (output.isReady() && 队列非空) 写一帧
写出失败（onError / isReady 永久 false / IO 异常）→ 关闭连接 → 现有断开回调
队列字节量超水位 → 主动 close（慢客户端断开）→ 客户端自动重连补发
close() → 置 closed、清队列、asyncContext.complete()（不再有阻塞写需要中断）
```

### 3.3 参考实现映射（"自研"部分的出处）

| 我们写的 | 参考自 |
|---|---|
| 非阻塞写泵 | Tomcat 官方 Async IO 文档 + `NumberWriter` 官方示例（`while (isReady()) { 取帧; 写 }` 骨架） |
| 回调契约假设 | Servlet 3.1 规范 Javadoc：同一连接回调绝不并发、isReady 语义 |
| 异步上下文与生命周期（startAsync/complete/onError/onTimeout 清理） | MCP Java SDK `HttpServletSseServerTransportProvider`（活跃维护，结构同构） |
| 边界竞态（入队 vs 关闭竞态等） | Atmosphere Broadcaster/AsyncIOWriter 源码（框架级同架构，查其处理方式） |
| 双容器行为验收 | 本仓库 spike IT（已就位） |

### 3.4 设计决策（评审点）

**D1. 队列满策略 = 主动断开该连接（推荐）**
断开不丢数据的依据：客户端重连带 Last-Event-ID → backfill 补发；重连报文也丢还有周期 sweep 兜底。
备选"阻塞入队"等于把现状问题搬回编排线程，排除。

**D2. delivered 语义 = 入队即推进 DELIVERED（推荐）**
状态 CAS 全部留在编排线程，容器回调线程只碰字节不碰状态——并发面最小。
正确性：DELIVERED 非终态，客户端未收到则重连补发/sweep 重推，at-least-once 兜底。

**D3. 容器异步总超时 setTimeout(0) 禁用（推荐）**
超时按请求总时长计，30s 必杀长连接，必须禁。连接活性靠心跳帧探测：
心跳入队 → 写失败 → 关闭重连。

### 3.5 新增配置（`ServerConfig`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `maxOutboundBytesPerConnection` | 8MB | 单连接出站队列字节水位；超限断开该连接 |

## 4. 改动范围

| 文件 | 改动 |
|---|---|
| `SseClientConnection` | 重写：同步写 → 队列 + 非阻塞泵；新增水位与关闭清理 |
| `SsePushServlet` | 订阅路径：注册 WriteListener、setTimeout(0)；`sendControl`/心跳路径不变（仍调 sendEvent/sendComment，内部变入队） |
| `ServerConfig` | +1 配置项（带默认值） |

**零改动**：LinkoraServer、DeliveryCoordinator、AckService、状态机、协议、存储、schema、
管理接口、指令通道、整个 client 模块、SsePushGateway 接口（RecordingGateway 等自实现不受影响）。

## 5. 风险清单与对策

| 风险 | 对策 |
|---|---|
| 入队与关闭竞态（连接正关闭时帧入队） | 队列入队前检查 closed（原子），泵侧关闭时 drain 清队；闭后入队直接返回 false |
| 容器回调时序差异（Jetty vs Tomcat） | 回调契约由 Servlet 规范保证；双容器验收测试强制通过 |
| 认证窗口（连接注册后、认证前被推送） | 保留现有 `readyForPush` 门禁逻辑不变 |
| 泵饿死（onWritePossible 未被再次触发） | 入队时若泵空闲则主动触发一次泵（标准做法：原子 flushing 标志） |
| 大 payload 内存峰值 | 水位按字节计（非帧数）；1MB×8 条即断开 |
| 状态机被破坏 | D2 保证状态 CAS 仍在编排线程；190 个现有单测回归 |

## 6. 验收标准（全部满足才算完成）

1. **既有语义**：190 个现有单测原样全绿
2. **隔离性**：慢客户端（订阅后不读）存在时，其他客户端接收延迟 <1s（现状 Tomcat：永远收不到）
3. **水位断开**：慢客户端队列到 8MB → 被断开 → 重连 → 消息补发完整
4. **双容器**：Jetty（testkit）+ Tomcat（Boot 真实上下文）均通过 2、3
5. **真机**：Boot / MVC 示例四向闭环 `[PASS]`
6. **变异验证**：破坏泵循环或水位检查 → 验收测试变红

## 7. 明确排除的路线（评审记录）

- Spring `SseEmitter`：`send()` 底层仍是同步阻塞写，解决不了本问题
- WebFlux/Netty/Atmosphere 引入：整套替换传输层，代价失配
- 虚拟线程：项目 target 1.8
- 每连接写线程（BIO）：隔离性成立但线程生命周期自研、天花板低——作为 A 路线失败时的降级预案保留
