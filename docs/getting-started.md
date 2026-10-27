# 接入指南（Getting Started）

> 适用版本：**0.6.0**。目标：10 分钟内在一个已有的 Spring 服务里跑通 linkora。
> 全部配置项说明见 [configuration.md](configuration.md)；老版本升级见 [history.md](history.md)。

## 一、选对你的包（按角色，一个宿主只引一个）

| 你的宿主 | 服务端（总部） | 客户端（终端/门店） |
|---|---|---|
| Spring Boot | `linkora-server-spring-boot-starter` | `linkora-client-spring-boot-starter` |
| Spring MVC | `linkora-server-spring` | `linkora-client-spring` |

```xml
<!-- 例：Spring Boot 客户端宿主 -->
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-client-spring-boot-starter</artifactId>
    <version>0.6.0</version>
</dependency>
```

> 0.6.0 起按角色拆包：客户端宿主的 classpath 里没有任何服务端类，反之亦然。
> 同一进程既做服务端又做客户端？两个包都引即可。

## 二、服务端接入（Spring Boot，3 步）

### 1. 引包 + 配置

```yaml
linkora:
  server:
    auth-mode: token              # 认证模式：token | hmac | anonymous
    register-clients:             # 启动时自动注册的客户端（幂等）
      store-001: true
  # 传输加密（默认开启，两端 key 必须一致；生成：openssl rand -base64 32）
  payload-cipher-key: "Qr1J5BCqFYkIlxfELSx6xYoZZ5YxYEZyWHZrBOJT83M="
```

首次启动自动建表（默认内嵌 SQLite，零外部依赖）；生产复用业务库见
[storage.md](storage.md)（`store=datasource`，与业务同事务）。

### 2. token 校验对接宿主账号体系（token 模式必配）

```java
@Service
public class HostTokenValidator implements TokenValidator {
    @Override
    public boolean validate(String clientId, String token) {
        // 回调你已有的登录体系：查会话表 / 调认证服务 / 校验 JWT 均可
        return sessionService.isValid(clientId, token);
    }
}
```

### 3. 业务代码：下发消息

```java
@Service
public class OrderService {
    private final LinkoraServer linkora;   // starter 自动装配，直接注入

    /** 下发：先落库再推送，客户端离线自动补投。 */
    public void notifyStore(String storeClientId, String orderNo, String item) {
        linkora.publish(storeClientId, "order-created",
                payload, orderNo);          // 第 4 参 = sequenceKey，同键严格按序
    }
}
```

## 三、客户端接入（Spring Boot，3 步）

### 1. 引包 + 配置

```yaml
linkora:
  client:
    server-url: http://总部地址:8082
    client-id: store-001
    auth-mode: token                   # 与服务端一致
    auth-token: "宿主登录体系下发的 token"   # 生产用 TokenProvider Bean（见下）
  payload-cipher-key: "与服务端一致"
```

生产推荐动态 token（每次请求自动携带最新值，过期续期对 SDK 透明）：

```java
@Bean
public TokenProvider tokenProvider() {
    return () -> hostSessionService.currentToken("store-001");
}
```

### 2. 业务代码：接收下发消息

```java
@Component
public class OrderListener implements MessageListener {
    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        // 正常返回 = 自动确认（PROCESSED）；抛异常 = 自动重试
        handleOrder(message.getPayload());
    }
}
// 注册：client.addListener("order-created", listener);
```

### 3. 业务代码：上行回传（带确认）

```java
uplinkPublisher.publish("order-status-updated", payload, orderNo);
// 本地队列先落库、后台自动发送重试；awaitServerAccepted 可等服务端确认
```

## 四、验证跑通

启动两端后（可直接运行 `examples/` 里的完整示例）：

- 客户端在线：消息实时送达（SSE 推送）
- 客户端离线：消息在服务端排队，客户端上线自动补投，零丢失
- 处理失败：抛异常自动重试，重试耗尽进死信（控制台处置，见 admin-console.md）

## 五、Spring MVC 宿主的差异

只有装配方式不同，业务代码完全一致：

- 服务端：`@Import(LinkoraServerConfiguration.class)`，四仓储 SPI 用 `@Bean` 提供
- 客户端：`@Import(LinkoraClientConfiguration.class)`，配置走 `linkora.client.*` 属性
- 完整可运行代码见 `examples/mvc-server`、`examples/mvc-client`

## 下一步阅读

| 想了解 | 文档 |
|---|---|
| 所有配置项 | [configuration.md](configuration.md) |
| 消息语义（顺序键/过期/死信/离线） | [messaging.md](messaging.md) |
| 认证与加密细节 | [security.md](security.md) |
| 存储模式（内嵌/宿主库同事务） | [storage.md](storage.md) |
| 运维控制台 | [admin-console.md](admin-console.md) |
| 遇到问题 | [troubleshooting.md](troubleshooting.md) |
| 老版本升级 | [history.md](history.md) |
