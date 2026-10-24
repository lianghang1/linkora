# 业务接入清单

上线前逐项确认。每项的背景与原理见对应文档链接。

## 1. 可靠语义（必须理解）

| 语义 | 业务侧责任 |
|---|---|
| 投递是**至少一次**（at-least-once） | 同一条消息可能重复送达——所有副作用必须幂等（推荐用 messageId 去重） |
| 下行确认默认自动 | 监听器正常返回即自动确认成功；失败时抛异常或显式调用 `ackFailed(code, msg)`，服务端按策略重试。`ackProcessed()` 仅在需要提前确认时使用 |
| 重试耗尽进**死信** | 死信不会自动恢复，需人工通过管理接口重放或跳过（见 [api.md](api.md)） |

详见 [reliability.md](reliability.md)。

## 2. 认证（生产必配）

- 服务端配置 `HmacClientAuthenticator`；不配置时服务端拒绝启动（开发环境可用
  `allow-anonymous=true` 显式放行，该开关不得出现在生产配置）
- 客户端配置 `HmacCredentialProvider(clientId, accessToken, secretBytes)`，两端凭据一致
- 失败指数锁定与防重放已内置；凭据管理约定见 [security.md](security.md)

## 3. clientId 规划

- **一个设备/项目 = 一个 clientId**（SDK 的隔离与顺序保证都以 clientId 为第一维度）
- 同一 clientId 被多台设备共用会导致消息被广播到多台、顺序语义失效
- 客户端必须在服务端 `register-clients` 中预注册（或通过注册接口登记）
- 多个业务线共用同一服务端时，各自使用独立 clientId；门禁天然按
  `(clientId, sequenceKey)` 隔离，无需在 key 里拼 clientId

## 4. 顺序保证（sequenceKey）

同一业务实体的状态消息（如订单状态 1 → 2）需要保证到达顺序时，发布方传同一个
`sequenceKey`（如订单号）：

```java
// 服务端下行
linkora.publish(clientId, "order-status", payload, orderId);
// 客户端上行
client.publish("order-status-updated", payload, orderId);
```

语义与边界（完整说明见 [reliability.md](reliability.md) 的"顺序保证"）：

- 同一 `clientId` + 同一 `sequenceKey`：严格按发布顺序投递与处理
- key 为 null / 空串 / 空白：与普通消息完全一致，不参与任何顺序约束
- 同键队头进死信或过期：放行后续（该条视为放弃）
- 键的粒度由业务权衡：细粒度（订单号）并行度好，粗粒度（店铺号）串行强但吞吐低

反模式：不要拼 clientId（SDK 已按其隔离）；不要拼 messageType（不同实体互相阻塞）；
不要对空键随机兜底（等于永远无序）。

## 5. 消息过期（expiresAt，建议启用）

```java
linkora.publish(clientId, type, payload, expiresAtMillis, traceId);
```

到期未送达的消息自动作废（EXPIRED 终态），停止重推。建议按业务时效设置
（如 5~10 分钟），既避免无效重推，也把异常场景的影响窗口封顶。

## 6. payload 规范

- 单条消息建议 ≤ 8KB；大文件/图片走对象存储，消息里只放引用
- 服务端硬上限：`message-size-limit-bytes`（默认 1MB HTTP body，对应原始 payload 约 786KB）
- payload 为业务自定义字节（推荐 JSON，编码 UTF-8）；traceId 字段支持全链路透传

## 7. 幂等与去重

- 服务端对上行按 messageId 幂等去重（客户端崩溃重发不会产生重复处理）
- 业务侧的重复投递防御：在监听器内用 `message.getMessageId()` 查重后再执行副作用
- 上行副作用（服务端 `UplinkHandler`）同样需要幂等：客户端至少一次投递，
  极端情况下同一条上行可能被处理多次

## 8. 运维与监控准备

- 熟悉管理接口（[api.md](api.md)）：客户端概览、积压/死信查询、死信重放/跳过、
  踢连接、运行时启停、每日统计
- 建议的巡检告警：`getClientOverviews()` 中 `unfinishedCount` 持续增长、
  `ACCEPTED` 状态停留超过数分钟的消息（推送链路异常信号）、死信数 > 0
- 设备无人值守场景：使用管理指令通道远程触发客户端死信重发/查询（见 api.md"指令通道"）
- 需要业务库事务与消息同生共死（发件箱语义）时，接事务接管（[custom-storage.md](custom-storage.md)）

## 9. 上线前检查清单

- [ ] 业务监听器失败路径明确（抛异常或显式 ackFailed），副作用幂等
- [ ] 服务端已配置 HMAC 认证，`allow-anonymous` 未出现在生产配置
- [ ] clientId 规划完成并预注册；需顺序的业务已确定键粒度
- [ ] 下行消息统一设置 expiresAt；payload 控制在 8KB 内
- [ ] 管理端点（如透出 HTTP）已加鉴权与操作审计
- [ ] 巡检告警接入（概览/积压/死信）
- [ ] 已在测试环境跑通双端四向流程（可参考 examples 双端工程）
