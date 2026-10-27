# 消息收发与业务语义（Messaging）

> 框架负责什么、业务在哪里接入、可靠语义的边界——一篇讲清。
> 配套可运行示例见 `examples/boot-*`（Boot 装配）与 `examples/mvc-*`（MVC 装配）。

## 一、框架管什么，业务管什么

| 端点 | 方法 | 归属 | 作用 |
|---|---|---|---|
| `/messages` | POST | 框架 | 客户端上报消息（上行） |
| `/acks` | POST | 框架 | 客户端 ACK 回传（下行确认） |
| `/events` | GET (SSE) | 框架 | 服务端下行推送通道 |

业务侧与框架只有一个约定：**实现监听器接口 + 注册**。收发、持久化、重试、
补投、去重、加密全在框架内部完成。

## 二、两条调用链

### 上行（客户端 → 服务端）

```
线下客户端              框架(Servlet)           IngestService            业务 Handler
    │  POST /messages         │                       │                        │
    │ ───────────────────────►│      ingest(msg)      │                        │
    │                         │ ─────────────────────►│ 认证→校验→持久化        │
    │                         │                       │ ── 按类型路由 ────────►│ handle()
    │ ◄───── 200 ACCEPTED ─────────────────────────── │                        │
```

业务侧接入点：写一个继承 `JsonUplinkHandler<T>`（payload 自动 JSON 反序列化）
或实现 `UplinkHandler` 的 `@Service`，按 `messageType()` 注册——starter 与 MVC
装配都会自动收集，零装配代码：

```java
@Service
public class OrderStatusHandler extends JsonUplinkHandler<StatusPayload> {
    public OrderStatusHandler() { super(StatusPayload.class); }
    public String messageType() { return "order-status-updated"; }
    protected void handle(LinkoraMessage message, StatusPayload payload) {
        // 业务处理；失败抛异常不影响上行 200（消息已持久化，可补偿）
    }
}
```

要点：回调在摄入线程同步执行，耗时业务自行异步化；可注册多个 `IngestListener`
做观察者扩展。

### 下行（服务端 → 客户端）

```
业务 Service            LinkoraServer             线下客户端框架            业务 Handler
    │  publish(topic,msg)      │                       │                        │
    │ ────────────────────────►│ 持久化 + 路由          │                        │
    │                          │ ── SSE /events 推送 ──►│                        │
    │                          │ ◄─ POST /acks ─────── │ 处理完成自动回 ACK      │
```

业务侧接入点：实现 `MessageListener` 并 `client.addListener(topic, listener)`
（可多个、start 后仍可注册）。**正常返回即自动确认**；失败时抛异常或显式
`message.ackFailed(code, msg)`——服务端按策略重试，重试耗尽进死信。

## 三、可靠语义（必须理解）

| 语义 | 说明 | 业务侧责任 |
|---|---|---|
| 至少一次（at-least-once） | 消息可能重复送达 | 副作用幂等（见下"去重"） |
| 先落库再投递 | 发布即持久化 | 无 |
| 离线补投 | 客户端离线时服务端排队，上线自动补投 | 无 |
| **SDK 分发层去重** | 重复投递/死信重放的消息，客户端按 inbox 状态识别并自动补 ACK，**业务监听器不会重复执行** | 无 |
| 崩溃窗口（毫秒级） | 处理成功后、确认持久化前进程崩溃 → 重启后该消息会再投一次 | 高危副作用（扣款/发货）在业务库用唯一键兜底 |
| 重试耗尽进死信 | 死信不自动恢复，控制台/管理接口人工处置 | 巡检 + 处置 |

## 四、顺序保证（sequenceKey）

同一业务实体的状态消息需要按序到达时，发布方传同一个 key（如订单号）：

```java
linkora.publish(clientId, "order-status", payload, orderId);   // 服务端下行
client.publish("order-status-updated", payload, orderId);      // 客户端上行
```

- 同一 `clientId + sequenceKey`：严格按发布顺序投递与处理
- key 为 null：与普通消息一致，不参与顺序约束
- 同键队头死信/过期：放行后续（视为放弃）
- 键粒度权衡：细粒度（订单号）并行度好；粗粒度（店铺号）串行强但吞吐低
- 反模式：不要拼 clientId（SDK 已按其隔离）；不要拼 messageType（不同实体互相阻塞）

## 五、消息过期（expiresAt）

```java
linkora.publish(clientId, type, payload, expiresAtMillis, traceId);
```

到期未送达自动作废（EXPIRED），停止重推。建议按业务时效设置（5~10 分钟），
把异常场景的影响窗口封顶。`traceId` 全链路透传。

## 六、payload 规范

- 推荐 JSON（UTF-8）；单条 ≤ 8KB，大文件走对象存储、消息只放引用
- 服务端硬上限：`message-size-limit-bytes`；超 64KB 上行自动 gzip（透明）
- 传输默认加密：线路与消息表中 payload 为密文，服务端解密后进业务逻辑

## 七、数据同步（DataSync，0.6.0）

业务系统**不改造**时，旁挂同步客户端只读源库：

```java
SyncDiff diff = dataSync.diffAndPublish(uplink, "tomb", "tomb-sync", rows, keyFn);
// 全量读取 N 行 → 快照 diff → 只发增量（+新增 ~修改 -删除），整表未变零上报
```

- 快照落在客户端存储（SQLite 文件或宿主库，随 `linkora.client.store` 走）
- 变更事件 sequenceKey=业务主键（同键保序）；大变更集自动分片保序
- 建议每日发一次整表指纹对账，线上不一致再触发真实全量
- 完整示例见 `examples/boot-client`（墓位数据同步）

## 八、clientId 规划

- **一个设备/终端 = 一个 clientId**（隔离与顺序保证的第一维度）
- 同一 clientId 被多台设备共用 → 消息广播、顺序失效
- 服务端 `register-clients` 预注册（或控制台/接口登记）

## 九、上线前检查清单

- [ ] 监听器失败路径明确（抛异常或 ackFailed），副作用幂等
- [ ] 认证已配置（token 模式的 TokenValidator / hmac 凭证）；`anonymous` 未出现在生产
- [ ] 两端 `payload-cipher-key` 已配置且一致（来自配置中心，不入代码库）
- [ ] clientId 规划完成并预注册；顺序键粒度已确定
- [ ] 下行消息统一设置 expiresAt；payload ≤ 8KB
- [ ] 告警接入：死信 Webhook（`alert-webhook-url`）或控制台收件箱巡检
- [ ] 测试环境跑通双端四向流程（参考 examples）
