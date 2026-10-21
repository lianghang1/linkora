# Linkora 示例：普通 Java 客户端

演示零 Spring 依赖接入 Linkora 客户端 SDK：SQLite 本地持久化队列、
SSE 下行监听、POST 上行、显式 ACK 语义、优雅关闭与重启续跑。

## 运行

先启动 [plain-java-server](../plain-java-server/README.md)，然后仓库根目录执行
（首次需先 `mvn install -DskipTests` 使各模块构件进入本地仓库）：

```bash
mvn -pl examples/plain-java-client exec:java \
    -Dexec.mainClass=com.linkora.example.client.PlainJavaClientMain \
    -Dexec.args="http://127.0.0.1:8080 demo-client"
```

预期输出：

- `[下行] topic=greeting payload=欢迎接入 Linkora！…`（服务端启动时下发的欢迎消息）
- `[上行] 已入队: demo-report-1..3`（随后被后台线程上报并被服务端接受）

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
