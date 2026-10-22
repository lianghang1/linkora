# Linkora

面向线上服务与多个线下 Java 客户端的可靠双端通信 SDK。

- 最低 JDK：8；宿主形态：**Spring Boot 2.x 或 Spring MVC 5.x**（也支持零框架宿主）
- 通信方案：SSE 下行推送 + HTTPS POST 上行
- 可靠性：服务端持久化 + 客户端本地队列 + 两级 ACK + 幂等 + 重试 + 断线补发
- 投递语义：至少一次（at-least-once），业务侧需自行保证副作用幂等
- 安全：纯应用层 HMAC 签名认证（不依赖证书/mTLS），失败指数锁定 + 防重放

## 快速开始：选你的宿主模式

无论服务端还是客户端，**每个角色只引 1 个 Maven 包**，存储实现随包传递，无需单独声明：

| 你的宿主 | 服务端引 | 客户端引 |
|---|---|---|
| Spring Boot 应用 | `linkora-spring-boot-starter` | `linkora-spring-boot-starter`（同一包） |
| Spring MVC 应用（无 Boot） | `linkora-spring` | `linkora-spring`（同一包） |

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId> <!-- 或 linkora-spring -->
    <version>0.2.0</version>
</dependency>
```

---

## 模式一：Spring Boot

### 服务端：给一个 DataSource 就能用

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

`application.yml`（其余全部自动装配，含建表迁移）：

```yaml
linkora:
  server:
    register-clients:            # 预注册的客户端 ID（→ 是否启用）
      store-001: true
```

下发消息 = 注入 `LinkoraServer` 调 `publish`：

```java
@Service
public class OrderService {
    private final LinkoraServer linkora;

    public OrderService(LinkoraServer linkora) { this.linkora = linkora; }

    public void notifyStore(String clientId, Object payload) {
        // 推给指定客户端；持久化成功后才返回，断线的客户端重连后自动补发
        linkora.publish(clientId, "order-created", payload);
    }
}
```

接收上行消息 = 实现一个 `UplinkHandler` bean（**推荐方式**：装配层自动收集全部
handler bean 经内置路由器按 messageType 分发，零装配代码；需要更泛化的回调
也可以直接实现 `IngestListener` bean）：

```java
@Service
public class OrderStatusHandler implements UplinkHandler {
    @Override
    public String messageType() { return "order-status-updated"; }

    @Override
    public void handle(LinkoraMessage message) {
        // 已持久化的上行消息在此处理（业务侧自行落库/分发并保证幂等）
    }
}
```

常用配置项（未列出的一律有安全默认值，未设置时无需关心）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `linkora.server.enabled` | `true` | 服务端自动装配开关 |
| `linkora.server.auto-migrate` | `true` | 启动时对宿主 DataSource 自动建表（幂等，可关） |
| `linkora.server.register-clients` | 空 | 预注册客户端及启用状态 |
| `linkora.server.heartbeat-interval-ms` | `15000` | SSE 心跳间隔 |

### 客户端：一个开关 + 三行配置

```yaml
linkora:
  client:
    enabled: true                # 默认关闭，显式开启
    server-url: http://127.0.0.1:8082
    client-id: store-001         # 与服务端 register-clients 一致
    # db-path: data/client.db    # 缺省 SQLite 本地队列，可省略
```

```java
@Service
public class OrderHandler implements MessageListener {
    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        // 收到服务端下发的 order-created，业务处理后必须显式 ACK
        message.ackProcessed();
    }
}
```

```java
@Configuration
public class LinkoraBinding {
    @Bean
    public CommandLineRunner bind(LinkoraClient client, OrderHandler handler) {
        return args -> client.addListener("order-created", handler);
    }
}
```

上行发送 = 注入 `UplinkPublisher`（客户端装配自动注册的 bean）调 `publish`
（写本地队列后自动发送与重试，崩溃不丢；`awaitServerAccepted` 可等"服务端已接受"确认）：

```java
String messageId = uplinkPublisher.publish("order-status-updated", payload); // 对象自动 JSON 序列化
boolean accepted = uplinkPublisher.awaitServerAccepted(messageId, 30_000);
```

---

## 模式二：Spring MVC（不依赖 Boot）

### 服务端：@Import + 四个存储 bean

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring</artifactId>
    <version>0.2.0</version>
</dependency>
```

装配类（`LinkoraServerConfiguration` 负责组装与生命周期，业务代码写法与 Boot 模式完全相同）：

```java
@Configuration
@EnableWebMvc
@Import(LinkoraServerConfiguration.class)
public class AppConfig {

    @Bean
    public DataSource linkoraDataSource() { /* 业务库连接池 */ }

    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        return new HmacClientAuthenticator(/* 凭据库 */); // 见"注意事项"
    }

    // 四个存储 SPI bean：用官方 JDBC 实现，或自实现接宿主业务库（事务接管）
    @Bean public ClientRegistry linkoraClientRegistry(DataSource ds) { return new JdbcClientRegistry(ds); }
    @Bean public MessageRepository linkoraMessageRepository(DataSource ds) { return new JdbcMessageRepository(ds); }
    @Bean public DeliveryRepository linkoraDeliveryRepository(DataSource ds) { return new JdbcDeliveryRepository(ds); }
    @Bean public ClientCursorRepository linkoraCursorRepository(DataSource ds) { return new JdbcClientCursorRepository(ds); }
}
```

三个 HTTP 端点（SSE 下行 `/events`、上行 `/messages`、确认 `/acks`）注册进任意 ServletContext：

```java
LinkoraServlets.register(linkoraServer, servletContext); // 一行
```

服务端调优与预注册等配置通过 `ServerConfig` bean 或存储 bean 装配时提供，清单同 Boot 模式。

### 客户端：@Import 显式启用

```xml
<!-- 同一个包，客户端能力随包传递 -->
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring</artifactId>
    <version>0.2.0</version>
</dependency>
```

```java
@Configuration
@Import(LinkoraClientConfiguration.class)   // 显式启用，不 import 就不会初始化客户端
public class ClientConfig { }
```

配置文件（任意 `@PropertySource`，配置键与 Boot 版逐字对齐）：

```properties
linkora.client.server-url=http://127.0.0.1:8081
linkora.client.client-id=store-001
linkora.client.db-path=data/client.db
```

业务代码与 Boot 模式完全相同：`MessageListener` 处理下行 + 显式 ACK，
`client.publish` 发上行，`addListener` 绑定 topic。

---

## 使用前必读

**三条铁律（违反会产生故障）**

1. **投递语义是至少一次**：同一条消息可能被重复送达，业务处理必须幂等（用消息 ID 去重）。
2. **下行消息必须显式 ACK**：处理完调用 `message.ackProcessed()`（失败调 `ackFailed(...)`），
   不 ACK 服务端会按策略无限重试。
3. **生产必须配 HMAC 认证**：不提供 `ClientAuthenticator` 时服务端回退为**放行全部请求**
   并打告警（仅限本地开发）。客户端侧把 `credentialProvider` 换成
   `HmacCredentialProvider(clientId, accessToken, secretBytes)`、服务端配
   `HmacClientAuthenticator` 即启用（两端凭据一致），失败锁定与防重放内置。

**版本与依赖约束**

| 约束 | 说明 |
|---|---|
| JDK ≥ 8 | Spring 5.x / Boot 2.x **全系兼容**（测试基线 5.3.31 / 2.7.18，宿主声明自己的版本即生效）；上限 Spring 6 / Boot 3 不支持（javax→jakarta） |
| sqlite-jdbc 版本必须唯一 | 宿主自身也用 SQLite 时，用 `dependencyManagement` 锁定为与 SDK 相同的 3.46.1.3，多版本共存会报 native 库初始化错误 |
| 全家桶传递依赖约 16MB | 纯服务端宿主在意体积时用 `<exclusions>` 排除 `linkora-client`（OkHttp 与 SQLite 随客户端传递）；未使用的类不初始化、native 库不加载，无运行时代价 |

**存储**：服务端复用宿主 `DataSource`（无需新增中间件，首次启动自动建表）；
客户端缺省 SQLite（WAL + FULL，进程崩溃/断电不丢消息）。宿主可用业务库接管存储
（事务性发件箱）——Boot 宿主定义自己的 `ClientRegistry`/`ClientStore` 等 bean 即可
自动让位；MVC 宿主自定义 bean 需加 `@Primary`（纯 Spring 没有
`@ConditionalOnMissingBean`）。完整可运行示例见 `examples/spring-tx-server`（服务端）
与 `examples/tx-client`（客户端）。

---

## 可运行示例

```bash
mvn install -DskipTests

# Spring Boot：终端 1 服务端（8082），终端 2 客户端（自动跑四向闭环输出 [PASS]）
mvn -pl examples/spring-boot-server/server-app spring-boot:run
mvn -pl examples/spring-boot-server/client-app spring-boot:run

# Spring MVC：终端 1 服务端（8081），终端 2 客户端
mvn -pl examples/spring-mvc-server/server-app compile exec:java "-Dexec.mainClass=com.linkora.example.springmvc.SpringMvcServerMain" "-Dexec.args=8081"
mvn -pl examples/spring-mvc-server/client-app compile exec:java "-Dexec.mainClass=com.linkora.example.mvcclient.ClientDemoApplication"
```

示例演示"线上下单/评价通知线下、线下回传订单状态/回复评价"四向闭环，
业务代码采用 controller/service 分层，可直接当接入模板抄；
零框架宿主示例（手动装配）见 `examples/plain-java-server` / `plain-java-client`。

## 构建

JDK 8 + Maven 3.6+：`mvn clean test`（全部自动化测试，无需外部中间件）。
