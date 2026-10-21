# Linkora 示例：普通 Java 服务端

演示如何在不引入 Spring 的前提下组装一个完整可用的 Linkora 服务端：
持久化（H2 文件库）+ SSE 下行推送 + 消息/ACK 上报端点。

## 运行

仓库根目录执行（首次需先 install 使各模块构件进入本地仓库）：

```bash
mvn install -DskipTests
mvn -pl examples/plain-java-server exec:java \
    -Dexec.mainClass=com.linkora.example.server.PlainJavaServerMain \
    -Dexec.args="8080 data/linkora-server"
```

启动后：

```
SSE 下行 : http://127.0.0.1:8080/events
消息上报 : http://127.0.0.1:8080/messages
ACK 上报 : http://127.0.0.1:8080/acks
演示客户端: demo-client（已预注册）
```

随后另开终端运行 [plain-java-client](../plain-java-client/README.md) 即可看到
端到端效果（下行欢迎消息 + 上行演示消息）。

## 生产注意事项

| 示例做法 | 生产替换 |
|---|---|
| 嵌入式 Jetty | 将 `SsePushServlet` / `LinkoraEndpointServlet` 注册进宿主已有 Servlet 3.1+ 容器 |
| H2 文件库 | 业务库（MySQL/PostgreSQL 等），`JdbcServerSchema.migrate` 自动建表 |
| `AllowAllClientAuthenticator` | `HmacClientAuthenticator` + 宿主 `CredentialStore` 实现（docs/security.md） |
| slf4j-simple | 宿主已有日志实现（SLF4J 门面不变） |
