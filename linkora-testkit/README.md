# linkora-testkit

测试支撑模块，仅供测试与示例验证使用，**不进入生产部署**。

## 内容

| 类 | 用途 |
|---|---|
| `EmbeddedLinkoraServer` | 一键启动完整服务端栈（H2 内存库 + Jetty + LinkoraServer 编排 + 三个端点），随机端口，供客户端集成测试 |
| `RecordingMessageListener` | 录制收到的下行消息供断言，可选自动 `ackProcessed`，支持 `awaitCount` 轮询等待 |
| `TestCredentialStore` | 内存版 HMAC 凭据库，配合 `HmacClientAuthenticator` 快速搭建认证测试环境 |

## 典型用法

```java
EmbeddedLinkoraServer server = EmbeddedLinkoraServer.builder()
        .registerClient("store-001")
        .build();
server.start();
try {
    LinkoraClient client = LinkoraClient.builder()
            .serverUrl(server.baseUrl())   // http://127.0.0.1:{随机端口}
            .clientId("store-001")
            .clientStore(store)
            .build();
    ...
} finally {
    server.close();
}
```

## 生产限制说明

- 持久化为 H2 内存库：重启即清空，仅适合测试；
- 容器为嵌入式 Jetty：生产环境请将 `SsePushServlet` / `LinkoraEndpointServlet`
  注册进宿主已有 Servlet 3.1+ 容器（见 docs/deployment.md）；
- 默认认证器为 `AllowAllClientAuthenticator`：仅测试环境可用，
  生产必须替换为 `HmacClientAuthenticator`（见 docs/security.md）。
