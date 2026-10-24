# 接入指南（Getting Started）

两种宿主形态：Spring Boot（starter 自动装配）与 Spring MVC（显式装配）。
两种形态的业务代码完全相同，差异只在装配入口。

服务端与客户端是两个角色，可以部署在同一进程（同机联调）或不同进程。

## 依赖引入

| 你的宿主 | 服务端引 | 客户端引 |
|---|---|---|
| Spring Boot 应用 | `linkora-spring-boot-starter` | `linkora-spring-boot-starter`（同一包） |
| Spring MVC 应用（无 Boot） | `linkora-spring` | `linkora-spring`（同一包） |

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId>
    <version>x.y.z</version>
</dependency>
```

## 一、Spring Boot 服务端

### 1. 配置 application.yml

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

生产环境必须配置认证器（见 [security.md](security.md)）；本地开发可显式设置
`allow-anonymous: true` 放行。

### 2. 下发消息

注入 `LinkoraServer`，持久化成功才返回；客户端离线时消息落库，重连后自动补发。

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

### 3. 接收上行

实现 `UplinkHandler` bean 即生效，自动按 `messageType()` 分发：

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

## 二、Spring Boot 客户端

### 1. 配置 application.yml

```yaml
linkora:
  client:
    enabled: true                        # 客户端开关，默认 false，必须显式开启
    server-url: http://127.0.0.1:8082    # 服务端地址
    client-id: store-001                 # 必须在服务端 register-clients 中
    # db-path: data/client.db            # SQLite 本地队列路径，可省略
```

### 2. 接收下行

实现 `MessageListener` 并注册。业务处理后必须显式 ACK，不 ACK 服务端会按策略重试：

```java
@Service
public class OrderHandler implements MessageListener {
    @Override
    public void onMessage(LinkoraReceivedMessage message) {
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

### 3. 发送上行

注入 `UplinkPublisher`，写本地 SQLite 队列后自动发送与重试，进程崩溃不丢：

```java
String messageId = uplinkPublisher.publish("order-status-updated", payload); // 对象自动 JSON 序列化
boolean accepted = uplinkPublisher.awaitServerAccepted(messageId, 30_000);   // 可选：等服务端接受确认
```

## 三、Spring MVC 服务端（不依赖 Boot）

以 Spring MVC + JPA + MySQL 项目为例：在现有项目上新增两个东西，不改动现有代码。

### 1. linkora 配置类

DataSource 复用 JPA 正在使用的那个，linkora 表与业务表同库，首次启动自动建表：

```java
@Configuration
@Import(LinkoraServerConfiguration.class)
public class LinkoraConfig {

    @Autowired
    private DataSource dataSource;

    // 四个存储 SPI bean：官方 JDBC 实现，直接复用上面的连接池；
    // 需要业务库事务接管（发件箱语义）时换成自实现，见 custom-storage.md
    @Bean public ClientRegistry linkoraClientRegistry() { return new JdbcClientRegistry(dataSource); }
    @Bean public MessageRepository linkoraMessageRepository() { return new JdbcMessageRepository(dataSource); }
    @Bean public DeliveryRepository linkoraDeliveryRepository() { return new JdbcDeliveryRepository(dataSource); }
    @Bean public ClientCursorRepository linkoraCursorRepository() { return new JdbcClientCursorRepository(dataSource); }

    /** 生产必配；未配置时服务端拒绝启动（开发环境可用 allow-anonymous 显式放行）。 */
    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        return new HmacClientAuthenticator(/* 凭据库 */);
    }
}
```

### 2. 注册三个端点（/events、/messages、/acks）

按项目启动方式二选一。

**方式 A：web.xml 项目**——自定义 `ServletContextListener`，在 web.xml 中声明在
`ContextLoaderListener` 之后：

```java
public class LinkoraBootstrapListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        WebApplicationContext ctx = WebApplicationContextUtils
                .getWebApplicationContext(event.getServletContext());
        LinkoraServlets.register(ctx.getBean(LinkoraServer.class), event.getServletContext());
    }
}
```

**方式 B：Java Config 项目**——在已有 Initializer 的 `onStartup` 末尾追加：

```java
@Override
public void onStartup(ServletContext servletContext) {
    super.onStartup(servletContext);
    WebApplicationContext ctx = WebApplicationContextUtils
            .getRequiredWebApplicationContext(servletContext);
    LinkoraServlets.register(ctx.getBean(LinkoraServer.class), servletContext);
}
```

之后注入 `LinkoraServer` 下发消息、实现 `UplinkHandler` 接收上行——业务代码与 Boot 完全相同。

## 四、Spring MVC 客户端

```java
@Configuration
@Import(LinkoraClientConfiguration.class)   // 不 import 就不会初始化客户端
public class ClientConfig { }
```

```properties
linkora.client.server-url=http://127.0.0.1:8081
linkora.client.client-id=store-001
linkora.client.db-path=data/client.db
```

配置键与 Boot 版逐字对齐；`MessageListener` / `UplinkPublisher` 用法与 Boot 完全相同。
注意：纯 Spring 无 `@ConditionalOnMissingBean`，自定义存储 bean 需加 `@Primary`。

## 五、服务端配置项参考

全部配置项均有安全默认值，仅需覆盖与默认不同的项。完整清单见
[deployment.md](deployment.md) 的"服务端配置参考"。

| 配置 | 默认 | 说明 |
|---|---|---|
| `register-clients` | 无 | 预注册客户端（客户端必须用其中之一的 clientId 接入） |
| `max-delivery-retries` | 10 | 投递重试上限，耗尽进死信 |
| `message-size-limit-bytes` | 1MB | 上行 HTTP body 上限（对应原始 payload 约 786KB） |
| `max-outbound-bytes-per-connection` | 8MB | 单连接推送队列水位，超限断开慢客户端 |
| `heartbeat-interval-ms` | 15000 | SSE 心跳间隔 |
| `sweep-interval-ms` | 5000 | 补发扫描周期 |
| `max-outbound-bytes-per-connection` | 8MB | 见 reliability.md"推送写模型" |

## 六、版本与依赖约束

| 约束 | 说明 |
|---|---|
| JDK ≥ 8 | Spring 5.x / Boot 2.x 全系兼容（测试基线 5.3.31 / 2.7.18）；Spring 6 / Boot 3 不支持（javax→jakarta） |
| sqlite-jdbc 版本必须唯一 | 宿主自身也用 SQLite 时，用 `dependencyManagement` 锁定为与 SDK 相同的 3.46.1.3，多版本共存会报 native 库初始化错误 |
| 全家桶传递依赖约 16MB | 纯服务端宿主在意体积时用 `<exclusions>` 排除 `linkora-client`（OkHttp 与 SQLite 随客户端传递）；未使用的类不初始化，无运行时代价 |

## 七、下一步

- 接入前的完整检查清单：[integration-guide.md](integration-guide.md)
- 可靠性语义（状态机、顺序保证、重试与死信）：[reliability.md](reliability.md)
- 生产认证配置：[security.md](security.md)
- 部署与数据库表：[deployment.md](deployment.md)
