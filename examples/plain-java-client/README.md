# Linkora 示例：普通 Java 客户端

演示零 Spring 依赖接入 Linkora 客户端 SDK：SQLite 本地持久化队列、
SSE 下行监听、POST 上行、显式 ACK 语义、优雅关闭与重启续跑。

消息处理采用 controller/service 式分层（与 spring-boot/mvc 示例同构）：

```
handler/OrderCreatedHandler.java   下行：处理线上"新订单"通知
handler/ReviewCreatedHandler.java  下行：处理线上"新评价"通知
service/OrderStatusService.java    上行：更改订单状态 → 通知线上
service/ReviewReplyService.java    上行：回复评价 → 通知线上
service/AbstractUplinkService.java 上行公共基类：publish + awaitServerAccepted
```

payload 统一为 JSON（如 `{"orderNo":"SO-1001","status":"SHIPPED"}`），
`awaitServerAccepted` 基于框架 `OutboxListener` 回调等待服务端确认（非轮询）。

## 运行

先启动 [plain-java-server](../plain-java-server/README.md)，然后仓库根目录执行
（首次需先 `mvn install -DskipTests` 使各模块构件进入本地仓库）：

```bash
mvn -pl examples/plain-java-client compile exec:java \
    -Dexec.mainClass=com.linkora.example.client.PlainJavaClientMain \
    -Dexec.args="http://127.0.0.1:8080 demo-client"
```

预期输出（四向闭环全部走通打印 PASS）：

- `[上行-订单状态] SO-1001 → SHIPPED 服务端已接受(SERVER_ACCEPTED)`
- `[上行-评价回复] RV-2001 ← 感谢反馈，已补发优惠券 服务端已接受(SERVER_ACCEPTED)`
- `[下行-新订单] 订单号=SO-2002 商品=机械键盘 x1 id=…`
- `[下行-新评价] 评价ID=RV-3001 内容=包装完好，五星好评 id=…`
- `[PASS] 四向闭环完成：2 条上行被服务端处理，2 条下行通知均收到并 ACK`

## 可靠性说明

- 上行消息先落 SQLite（outbox）再后台发送，进程崩溃后重启自动续传；
- 下行消息先落 SQLite（inbox 去重）再回调业务监听器，ACK 丢失会被服务端补推；
- 监听器内调用 `message.ackProcessed()` 表示业务成功；
  失败用 `message.ackFailed(LinkoraErrorCode.XXX, "原因")`，客户端按配置重试；
- 直接 kill -9 也安全：消息状态均已持久化，重启后自动恢复。

## 启用 HMAC 认证

```java
.credentialProvider(new HmacCredentialProvider(clientId, accessToken, secretBytes))
```

服务端对应配置 `HmacClientAuthenticator`，完整说明见 docs/security.md。
