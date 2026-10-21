# Linkora

面向线上服务与多个线下 Java 客户端的可靠双端通信 SDK。

- 最低 JDK：8（构建与测试在真实 JDK 8 上执行）
- 通信方案：SSE 下行 + HTTPS POST 上行
- 可靠性：服务端持久化 + 客户端独立 SQLite + 接收/处理两级 ACK + 幂等 + 重试 + 断线补发
- 投递语义：至少一次（at-least-once），业务侧需自行保证副作用幂等
- 安全：纯应用层 HMAC 签名认证（不依赖证书/mTLS），失败指数锁定 + 防重放

详细设计见 `Linkora_最终开发方案.md` 与 `docs/`。

## 模块

| 模块 | 职责 |
|---|---|
| linkora-core | 消息模型、状态机、错误码、序列化抽象、存储 SPI、安全工具 |
| linkora-client | 客户端编排：SQLite 优先、SSE 下行、POST 上行、ACK 流程、断网补传 |
| linkora-server | 服务端编排：持久化优先、SSE 推送、上报/ACK 接入、重试与死信 |
| linkora-client-storage-sqlite | 客户端 SQLite 持久化（WAL + synchronous=FULL） |
| linkora-server-storage-jdbc | 服务端 JDBC 持久化（自研版本迁移，复用现有数据库） |
| linkora-transport-sse / linkora-transport-http | OkHttp 4.x 传输实现 |
| linkora-server-servlet | Servlet 3.1 异步适配器（嵌入宿主已有容器） |
| linkora-serialize-jackson | Jackson JSON 编解码（仅用 2.13+ 稳定 API） |
| linkora-testkit | 测试支撑：嵌入式服务器、录制监听器、测试凭据库（不进生产） |
| examples/plain-java-server, examples/plain-java-client | 可运行的普通 Java 端到端示例 |

Spring 集成模块（linkora-spring / spring-boot-starter）尚未提供，
Spring MVC 宿主可直接按 docs/deployment.md 注册 Servlet 接入。

## 构建

要求 JDK 8 + Maven 3.6+。

```bash
mvn clean test
```

## 快速上手

```bash
mvn install -DskipTests
# 终端 1：启动服务端示例
mvn -pl examples/plain-java-server exec:java "-Dexec.mainClass=com.linkora.example.server.PlainJavaServerMain" "-Dexec.args=8080 data/linkora-server"
# 终端 2：启动客户端示例
mvn -pl examples/plain-java-client exec:java "-Dexec.mainClass=com.linkora.example.client.PlainJavaClientMain" "-Dexec.args=http://127.0.0.1:8080 demo-client"
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 架构与模块边界 |
| [docs/protocol.md](docs/protocol.md) | 线路协议与消息/ACK 结构 |
| [docs/reliability.md](docs/reliability.md) | 可靠投递语义与状态机 |
| [docs/security.md](docs/security.md) | HMAC 认证、防爆破、凭据管理 |
| [docs/deployment.md](docs/deployment.md) | 部署、配置、升级与备份 |
| [docs/compatibility.md](docs/compatibility.md) | JDK/容器/数据库/依赖兼容性矩阵 |
| [docs/troubleshooting.md](docs/troubleshooting.md) | 常见问题排查 |
