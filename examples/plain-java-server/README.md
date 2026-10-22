# Linkora 示例：普通 Java 服务端

演示如何在不引入 Spring 的前提下组装一个完整可用的 Linkora 服务端（线上平台侧）：
持久化（H2 文件库）+ SSE 下行推送 + 消息/ACK 上报端点 + 上行消息业务处理。

消息处理采用 controller/service 式分层（与 spring-boot/mvc 示例同构），
演示框架 `IngestListener` 的手动接入方式：

```
controller/PlatformController.java 业务 HTTP 接口（下单/评价/查上行）
service/OrderService.java          下行：下单 → 通知线下
service/ReviewService.java         下行：评价 → 通知线下
service/uplink/UplinkMessageDispatcher.java  框架 IngestListener 唯一接入点
service/uplink/OrderStatusHandler.java       上行：订单状态变更
service/uplink/ReviewReplyHandler.java       上行：评价回复
```

payload 统一为 JSON。接入点唯一：Main 里
`LinkoraServer.builder()...ingestListener(dispatcher)`，分发器再按
messageType 路由到两个 UplinkHandler。

## 运行

仓库根目录执行（首次需先 install 使各模块构件进入本地仓库）：

```bash
mvn install -DskipTests
mvn -pl examples/plain-java-server compile exec:java \
    -Dexec.mainClass=com.linkora.example.server.PlainJavaServerMain \
    -Dexec.args="8080 data/linkora-server"
```

启动后：

```
SSE 下行   : http://127.0.0.1:8080/events
消息上报   : http://127.0.0.1:8080/messages
ACK 上报   : http://127.0.0.1:8080/acks
线上下单   : POST http://127.0.0.1:8080/api/orders
线上评价   : POST http://127.0.0.1:8080/api/reviews
查上行状态 : GET  http://127.0.0.1:8080/api/uplink/order-status-updates
查上行回复 : GET  http://127.0.0.1:8080/api/uplink/review-replies
演示客户端 : demo-client（已预注册）
```

随后另开终端运行 [plain-java-client](../plain-java-client/README.md) 即可看到
四向闭环端到端效果（上行被处理 + 下行通知均 ACK）。

## 生产注意事项

| 示例做法 | 生产替换 |
|---|---|
| 嵌入式 Jetty | 将 `SsePushServlet` / `LinkoraEndpointServlet` 注册进宿主已有 Servlet 3.1+ 容器 |
| H2 文件库 | 业务库（MySQL 5.7+/8、PostgreSQL 等），`JdbcServerSchema.migrate` 自动建表 |
| `AllowAllClientAuthenticator` | `HmacClientAuthenticator` + 宿主 `CredentialStore` 实现（docs/security.md） |
| slf4j-simple | 宿主已有日志实现（SLF4J 门面不变） |
