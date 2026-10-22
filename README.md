# Linkora

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lianghang1/linkora-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.lianghang1/linkora-spring-boot-starter)

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
    <version>x.y.z</version>
</dependency>
```

> 示例中的 `x.y.z` 一律替换为最新版本（上方徽章实时显示）。

---

## 模式一：Spring Boot

### 服务端应用

**第 1 步：引入依赖**

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId>
    <version>x.y.z</version>
</dependency>
```

**第 2 步：application.yml 配置**（其余全部自动装配，含首次启动自动建表）

```yaml
linkora:
  server:
    enabled: true                # 服务端开关，默认 true
    auto-migrate: true           # 启动时对宿主 DataSource 自动建表（幂等）
    register-clients:            # 预注册客户端 ID（客户端必须用其中之一接入）
      store-001: true
    # events-path: /events       # SSE 下行端点，默认 /events
    # messages-path: /messages   # 上行端点
    # acks-path: /acks           # ACK 端点
```

**第 3 步：下发消息（注入 `LinkoraServer`，持久化成功才返回，客户端断线时重连后自动补发）**

```java
@Service
public class OrderService {
    private final LinkoraServer linkora;

    public OrderService(LinkoraServer linkora) { this.linkora = linkora; }

    public void notifyStore(String clientId, Object payload) {
        linkora.publish(clientId, "order-created", payload);
    }
}
```

**第 4 步：接收上行（实现 `UplinkHandler` bean 即生效，自动按 `messageType()` 分发，零装配代码）**

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

其余配置项均有安全默认值，无需关心。

### 客户端应用

**第 1 步：引入依赖**（与服务端同一个 starter 包）

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId>
    <version>x.y.z</version>
</dependency>
```

**第 2 步：application.yml 配置**

```yaml
linkora:
  client:
    enabled: true                        # 客户端开关，默认 false，必须显式开启
    server-url: http://127.0.0.1:8082    # 服务端地址
    client-id: store-001                 # 必须在服务端 register-clients 中
    # db-path: data/client.db            # SQLite 本地队列路径，可省略
```

**第 3 步：接收下行（实现 `MessageListener` 并注册，业务处理后必须显式 ACK）**

```java
@Service
public class OrderHandler implements MessageListener {
    @Override
    public void onMessage(LinkoraReceivedMessage message) {
        // 收到 order-created 推送，业务处理后显式 ACK（不 ACK 服务端会无限重试）
        message.ackProcessed();
    }
}

@Configuration
public class LinkoraBinding {
    @Bean
    public CommandLineRunner bind(LinkoraClient client, OrderHandler handler) {
        return args -> client.addListener("order-created", handler);
    }
}
```

**第 4 步：发送上行（注入 `UplinkPublisher`，写本地队列后自动发送与重试，崩溃不丢）**

```java
String messageId = uplinkPublisher.publish("order-status-updated", payload); // 对象自动 JSON 序列化
boolean accepted = uplinkPublisher.awaitServerAccepted(messageId, 30_000);   // 可选：等服务端接受的确认
```

---

## 模式二：Spring MVC（不依赖 Boot）

### 服务端应用

**第 1 步：引入依赖**

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring</artifactId>
    <version>x.y.z</version>
</dependency>
```

**第 2 步：装配配置类（`@Import` 启用，提供 DataSource 与四个存储 bean）**

```java
@Configuration
@EnableWebMvc
@Import(LinkoraServerConfiguration.class)
public class AppConfig {

    @Bean
    public DataSource linkoraDataSource() { /* 业务库连接池 */ }

    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        return new HmacClientAuthenticator(/* 凭据库 */); // 生产必配，写法见"使用前必读"第 3 条
    }

    // 四个存储 SPI bean：官方 JDBC 实现，或自实现接宿主业务库（事务接管）
    @Bean public ClientRegistry linkoraClientRegistry(DataSource ds) { return new JdbcClientRegistry(ds); }
    @Bean public MessageRepository linkoraMessageRepository(DataSource ds) { return new JdbcMessageRepository(ds); }
    @Bean public DeliveryRepository linkoraDeliveryRepository(DataSource ds) { return new JdbcDeliveryRepository(ds); }
    @Bean public ClientCursorRepository linkoraCursorRepository(DataSource ds) { return new JdbcClientCursorRepository(ds); }
}
```

**第 3 步：注册三个 HTTP 端点（SSE 下行 `/events`、上行 `/messages`、确认 `/acks`）**

```java
LinkoraServlets.register(linkoraServer, servletContext); // 一行
```

之后注入 `LinkoraServer` 下发消息、实现 `UplinkHandler` 接收上行——代码与 Boot 模式完全相同。

### 客户端应用

**第 1 步：引入依赖**（同一个包，客户端能力随包传递）

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring</artifactId>
    <version>x.y.z</version>
</dependency>
```

**第 2 步：装配配置类（显式 `@Import`，不 import 就不会初始化客户端）**

```java
@Configuration
@Import(LinkoraClientConfiguration.class)
public class ClientConfig { }
```

**第 3 步：properties 配置（配置键与 Boot 版逐字对齐）**

```properties
linkora.client.server-url=http://127.0.0.1:8081
linkora.client.client-id=store-001
linkora.client.db-path=data/client.db
```

之后实现 `MessageListener` 接收下行并显式 ACK、注入 `UplinkPublisher` 发送上行——代码与 Boot 模式完全相同。

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

`examples/` 目录下每个场景都有开箱即跑的工程（Boot / MVC 双端、零框架、宿主事务接管），
工程结构即推荐接入结构，直接参考或复制：

```bash
mvn install -DskipTests

# Spring Boot：终端 1 起服务端（8082），终端 2 起客户端（自动跑四向闭环输出 [PASS]）
mvn -pl examples/spring-boot-server/server-app spring-boot:run
mvn -pl examples/spring-boot-server/client-app spring-boot:run

# Spring MVC：终端 1 起服务端（8081），终端 2 起客户端
mvn -pl examples/spring-mvc-server/server-app compile exec:java "-Dexec.mainClass=com.linkora.example.springmvc.SpringMvcServerMain" "-Dexec.args=8081"
mvn -pl examples/spring-mvc-server/client-app compile exec:java "-Dexec.mainClass=com.linkora.example.mvcclient.ClientDemoApplication"
```

## 构建

JDK 8 + Maven 3.6+：`mvn clean test`（全部自动化测试，无需外部中间件）。
