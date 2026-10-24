# Linkora

[![Maven Central](https://img.shields.io/maven-central/v/io.github.lianghang1/linkora-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.lianghang1/linkora-spring-boot-starter)

面向线上服务与多个线下 Java 客户端的可靠双端通信 SDK。

- 最低 JDK：8；宿主形态：**Spring Boot 2.x 或 Spring MVC 5.x**
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

以 **Spring MVC + JPA + MySQL** 项目为例：在现有项目上**新增两个东西**即可，不改动现有代码。

**新增 1：linkora 配置类**（DataSource 复用 JPA 正在用的那个，linkora 表与业务表同库，首次启动自动建表）

```java
@Configuration
@Import(LinkoraServerConfiguration.class)
public class LinkoraConfig {

    /** 你项目里已有的 DataSource（HikariCP + MySQL），不需要新建。 */
    @Autowired
    private DataSource dataSource;

    // 四个存储 SPI bean：官方 JDBC 实现，直接复用上面的连接池；
    // 想用业务库事务接管（发件箱语义）时换成自实现即可
    @Bean public ClientRegistry linkoraClientRegistry() { return new JdbcClientRegistry(dataSource); }
    @Bean public MessageRepository linkoraMessageRepository() { return new JdbcMessageRepository(dataSource); }
    @Bean public DeliveryRepository linkoraDeliveryRepository() { return new JdbcDeliveryRepository(dataSource); }
    @Bean public ClientCursorRepository linkoraCursorRepository() { return new JdbcClientCursorRepository(dataSource); }

    /** 生产必配；本地开发可先省略（会放行全部请求并打告警）。 */
    @Bean
    public ClientAuthenticator linkoraClientAuthenticator() {
        return new HmacClientAuthenticator(/* 凭据库 */);
    }
}
```

**新增 2：注册三个端点**（SSE 下行 `/events`、上行 `/messages`、确认 `/acks`）——按项目的启动方式二选一

**方式 A：web.xml 项目**（`ContextLoaderListener` 启动，传统 Spring MVC 多为此形态）

自定义一个 `ServletContextListener`：

```java
public class LinkoraBootstrapListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent event) {
        // root 容器已就绪（本 listener 在 web.xml 中声明在 ContextLoaderListener 之后）
        WebApplicationContext ctx = WebApplicationContextUtils
                .getWebApplicationContext(event.getServletContext());
        LinkoraServlets.register(ctx.getBean(LinkoraServer.class), event.getServletContext());
    }
}
```

web.xml 中声明在 `ContextLoaderListener` **之后**（保证取 bean 时容器已初始化完成）：

```xml
<listener>
    <listener-class>org.springframework.web.context.ContextLoaderListener</listener-class>
</listener>
<listener>
    <listener-class>com.example.linkora.LinkoraBootstrapListener</listener-class>
</listener>
```

**方式 B：Java Config 项目**（`AbstractAnnotationConfigDispatcherServletInitializer` 启动）

在已有的 Initializer 的 `onStartup` 末尾加三行：

```java
@Override
public void onStartup(ServletContext servletContext) {
    super.onStartup(servletContext);
    WebApplicationContext ctx = WebApplicationContextUtils
            .getRequiredWebApplicationContext(servletContext);
    LinkoraServlets.register(ctx.getBean(LinkoraServer.class), servletContext);
}
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

## 服务端还提供什么（不只是收发消息）

可靠投递本身是自动的：先落库再推送、断线客户端重连自动补发、失败指数退避重试、
到期自动作废（EXPIRED）、重试耗尽进死信（DEAD_LETTER）——全部无需业务代码参与。
在此基础上，注入 `LinkoraServer` 后还可以：

| 能力 | 用法 | 说明 |
|---|---|---|
| 消息投递状态查询 | `linkora.getMessageStatus(messageId)` | 消息正文 + 每个目标的投递记录（状态、序号、重试次数）；`isSettled()` 一键判断是否全部到达终态，可用于对账 |
| 客户端积压与在线查询 | `linkora.getBacklog(clientId)` | 未完成投递数 + 是否有活跃 SSE 连接，适合监控告警 |
| 带过期与链路追踪的发布 | `linkora.publish(clientId, type, payload, expiresAt, traceId)` | 到期未送达自动作废；traceId 透传到客户端便于全链路排查 |
| 顺序投递（sequenceKey） | `linkora.publish(clientId, type, payload, orderId)` | 同键消息严格按发布顺序投递（如订单状态 1→2 不会倒序到达）；key 为空则与普通消息无异。详见 [docs/reliability.md](docs/reliability.md) |
| **管理接口（监控与人工处置）** | 见下方 [管理接口](#管理接口监控与人工处置) 章节 | 客户端概览、消息列表/详情、死信重放、跳过、踢连接、运行时启停、每日统计——全部人类可读视图，出状况不用翻库 |
| 手动触发补发扫描 | `linkora.sweepOnce()` | 运维/测试用，正常运行不需要 |

上行侧同样有保障：`UplinkHandler` 按 `messageType()` 自动分发，服务端按 messageId 幂等去重
（客户端崩溃重发不会产生重复处理）。

客户端侧注入 `LinkoraClient` 后：`getSseState()` 查询连接状态；所有上行先落本地 SQLite
再后台发送，进程崩溃/断电后自动补传。

---

## 管理接口：监控与人工处置

出状况时不用翻库。完整方法签名、入参表与返回字段说明见 [docs/api.md](docs/api.md)。注入 `LinkoraServer` 后即可调用，全部返回**人类可读视图**：
时间一律 `yyyy-MM-dd HH:mm:ss`（无裸时间戳），payload 一律 UTF-8 文本预览（超长截断、
二进制只标注字节数，**绝不输出 Base64**），状态附中文描述（"重试中"、"死信，等待人工介入"…）。

### 查询：看清楚现在压着什么

| 方法 | 输出 |
|---|---|
| `getClientOverviews()` | 每个客户端：是否在线、连接数、未完成数、死信数、注册时间 |
| `listUnfinishedDeliveries(clientId, limit)` | 未终结投递明细（含死信）：messageId、seq、中文状态、重试次数、下次重试时间、最后错误、payload 预览 |
| `listDeadLetters(clientId, limit)` | 死信列表（进入死信的原因在 lastError） |
| `listMessages(clientId?, messageType?, beginTime?, endTime?, page, size)` | 消息列表多条件筛选 + 分页，每条附各目标投递状态中文摘要 |
| `getMessageDetail(messageId)` | 完整 payload 明文 + 全部投递记录 |
| `getDailyStats(days)` | 每日发布量 + 全部投递记录的状态分布 |

```java
// 例：一眼看清每个客户端的情况
for (ClientOverviewView c : linkora.getClientOverviews()) {
    if (c.isOnline() && c.getDeadLetterCount() > 0) {
        log.warn("客户端 {} 在线但有 {} 条死信待处理", c.getClientId(), c.getDeadLetterCount());
    }
}

// 例：某客户端积压了什么（时间/内容直接可读）
linkora.listUnfinishedDeliveries("store-001", 50)
       .forEach(d -> log.info("[{}] seq={} 重试{}次 错误={} payload={}",
               d.getStateText(), d.getSeq(), d.getRetryCount(),
               d.getLastError(), d.getPayloadPreview()));
```

### 处置：人工干预的手段

| 方法 | 行为 |
|---|---|
| `disconnectClient(clientId)` | **踢掉假在线连接**：强制断开该客户端全部 SSE 连接，客户端自动重连并按 Last-Event-ID 补发，不丢数据 |
| `retryDeadLetter(clientId, messageId)` | 死信重放：DEAD_LETTER → RETRYING 并立即重推 |
| `skipMessage(clientId, messageId)` | 跳过卡住的消息（→ EXPIRED 终态，lastError 留痕），顺序键场景下同键后续立即放行 |
| `retryAllDeadLetters(clientId, limit)` / `skipAllDeadLetters(clientId, limit)` | 死信批量重放 / 批量跳过 |
| `forceTransition(clientId, messageId, targetState, reason)` | 通用人工状态变更：状态机校验合法性，reason 写入 lastError 留痕；非法转移显式报错（上面两个方法是它的便捷特例） |
| `enableClient(clientId)` / `disableClient(clientId)` | 运行时启停客户端（禁用后拒收新消息、拒绝接入，恢复即用） |

```java
// 例：假在线客户端（断网/断电没被发现）拖着重试——踢掉它，让它重连补发
linkora.disconnectClient("store-001");

// 例：线下已手工把数据修好，死信不再需要推送——批量跳过并留痕
linkora.skipAllDeadLetters("store-001", 100);

// 例：通用人工状态变更（reason 必填，用于操作审计）
linkora.forceTransition("store-001", messageId,
        MessageState.PROCESSED, "线下已手工导入该数据");
```

**设计边界（必读）**

- SDK 只提供方法、不做鉴权；HTTP 暴露由宿主自行实现并**必须加访问控制**
  （参考 `examples/spring-boot-server/server-app` 的 `AdminController`，仅演示用）。
  所有处置方法的 `reason` 会写入 `lastError` 留痕，建议接入操作审计。
- 这些方法全部是旁路实现（纯查询 + 单条 CAS，复用投递状态机已有的合法转移），
  不触碰推送/重试/补发主链路，不引入任何后台线程。
- `skipMessage` / `skipAllDeadLetters` 语义是"主动放弃该条"——跳过前确认业务上可以放弃。
- 自动化程度更高（指标面板、Grafana）不在 SDK 范围内，需要时在宿主侧接入 metrics。

### 客户端侧：本地队列查询与上行死信处置

上行的"死信" = 发送耗尽重试 / 被服务端不可重试拒绝（如认证配置错误）/ 消息过期。
死信不会自动复活（数据不丢，留在本地 SQLite），注入 `LinkoraClient` 后可本地处置：

| 方法 | 输出/行为 |
|---|---|
| `listOutbox(status?, limit)` | 上行队列明细（全部状态，中文状态描述 + payload 文本预览） |
| `listInbox(processStatus?, limit)` | 下行收件明细（接收/处理两级中文状态） |
| `listOutboxDeadLetters(limit)` | 上行死信列表 |
| `retryOutboxDeadLetter(messageId)` | 重发：DEAD_LETTER → PENDING（重试计数清零）并立即触发发送 |
| `discardOutboxDeadLetter(messageId, reason)` | 放弃留痕 |

### 服务端远程触发客户端运营操作（管理指令通道）

客户端往往无人值守——服务端提供**远程指令通道**：通过现有 SSE 连接下发 `ctrl` 事件，
**不落库、不混业务消息、仅对在线设备实时生效**（离线返回未送达，运维再次触发即可；
设备离线时重发类指令本来也无法执行）。

```java
// 例：远程触发门店设备重发它的上行死信（典型场景：凭据配错修复后）
linkora.retryClientOutboxDeadLetter("store-001", messageId);   // delivered=0 表示设备离线

// 例：探测设备指令通道连通性
linkora.pingClient("store-001");

// 例：远程查询设备本地队列（结果经上行回传，按 queryId 读取）
String queryId = linkora.queryClientOutbox("store-001", null, 50);
String result = linkora.getCommandResult(queryId);   // 尚未返回时为 null，稍后重查
```

指令白名单（`com.linkora.core.admin.ClientCommands`）：`RETRY_OUTBOX_DEAD_LETTER`、
`DISCARD_OUTBOX_DEAD_LETTER`、`QUERY_OUTBOX`、`PING`——管理通道不是通用执行通道，
每条指令显式定义，未知指令客户端忽略并告警。指令本身不持久化不重试（收不到就再触发一次），
指令执行结果经上行回传并在服务端内存暂存（按 queryId 读取，最多保留 100 条）。

---

## 数据库表（会建哪些表、动你什么数据）

**服务端：在你的业务库里建 6 张 `lk_` 前缀表**。首次启动自动建表（全部 `IF NOT EXISTS`
幂等迁移），**不会触碰你的任何业务表**；linkora 表与业务表同库共存，互不干扰。

| 表 | 用途 |
|---|---|
| `lk_message` | 下行消息正文：messageId、类型、payload（base64）、过期时间、traceId |
| `lk_delivery` | 每条消息对每个客户端的投递状态：状态机、投递序号、重试次数、下次重试时间、最后错误——**可靠性核心表** |
| `lk_client` | 客户端注册与在线状态：启用/禁用、SSE 连接状态、最后活跃时间 |
| `lk_client_seq` | 每客户端投递序号分配（行锁串行化，保证并发下无重复、无跳号） |
| `lk_client_cursor` | 客户端 ACK 游标（断线重连续传的依据） |
| `lk_schema_version` | SDK 表结构版本号（自动迁移用） |

**客户端：SQLite 单文件**（`db-path` 指定，缺省路径自动创建），4 张表：

| 表 | 用途 |
|---|---|
| `lk_outbox` | 上行发件队列：先落库再发送，含状态/重试计数/下次重试时间，崩溃后自动补传 |
| `lk_inbox` | 下行收件队列：先落库再回调业务，接收/处理两级状态，去重与 ACK 补发的依据 |
| `lk_meta` | SDK 元数据（本地游标等） |
| `lk_schema_version` | 客户端本地结构版本号 |

**payload 存储与大小上限**：payload 列已按 LONGTEXT 建表（v2 迁移自动完成），
存储不再是瓶颈；真正的上限是 `message-size-limit-bytes`（HTTP body，默认 1MB，
对应原始 payload 约 786KB），Servlet 读取闸门与业务校验共用这一个值。
**升级与维护**：后续版本的结构变更由 SDK 自动顺序迁移（按 `lk_schema_version` 记录逐版本
执行，中途崩溃重启自动续跑），宿主**无需手动执行任何 SQL**。所有时间字段统一存 epoch
毫秒（整数），跨时区、跨数据库一致。

---

## 换掉内置存储：自定义存储与事务接管

内置存储开箱即用，但**两端都支持换成自己的实现**。下面按真实项目形态给出完整接法，
所有代码模式与 `examples/spring-tx-server` / `examples/tx-client` 可运行示例一致。

### 场景一：Spring Boot + JPA + MySQL 服务端——业务与消息同一事务（事务性发件箱）

项目已有 `spring-boot-starter-data-jpa` + `mysql-connector-j` + 本 SDK 的 starter，
`application.yml` 里 JPA 与 linkora 配置共存：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/store?useUnicode=true&characterEncoding=utf8
    username: store
    password: xxx
  jpa:
    hibernate:
      ddl-auto: validate

linkora:
  server:
    register-clients:
      store-001: true
```

新增一个配置类：**四个存储 SPI 的连接取自"事务感知数据源"**（包住 JPA 在用的那个
DataSource，同一个连接池、同一个库），覆盖 starter 的默认装配：

```java
@Configuration
public class LinkoraConfig {

    /** JPA 自动配置的那个 DataSource（HikariCP + MySQL），直接注入，不新建。 */
    @Bean
    public TransactionAwareDataSourceProxy linkoraTxDataSource(DataSource dataSource) {
        return new TransactionAwareDataSourceProxy(dataSource);
    }

    // 四个存储 SPI：传事务感知数据源；@Transactional 内的持久化自动加入当前事务
    @Bean public MessageRepository linkoraMessageRepository(TransactionAwareDataSourceProxy ds) { return new JdbcMessageRepository(ds); }
    @Bean public DeliveryRepository linkoraDeliveryRepository(TransactionAwareDataSourceProxy ds) { return new JdbcDeliveryRepository(ds); }
    @Bean public ClientRegistry linkoraClientRegistry(TransactionAwareDataSourceProxy ds) { return new JdbcClientRegistry(ds); }
    @Bean public ClientCursorRepository linkoraCursorRepository(TransactionAwareDataSourceProxy ds) { return new JdbcClientCursorRepository(ds); }
}
```

业务代码照常写 `@Transactional`，业务表与消息表一起提交/回滚：

```java
@Service
public class OrderService {

    private final OrderRepository orderRepository;   // 你的 JPA Repository
    private final LinkoraServer linkora;

    public OrderService(OrderRepository orderRepository, LinkoraServer linkora) {
        this.orderRepository = orderRepository;
        this.linkora = linkora;
    }

    @Transactional
    public void placeOrder(Order order) {
        orderRepository.save(order);                                    // 业务表
        linkora.publish(order.getStoreId(), "order-created", order);    // lk_message / lk_delivery 同一事务
        // 后续任意一步抛异常 → 订单行与消息一起回滚，
        // 客户端永远看不到"失败订单"的通知；正常返回 → 一起提交后按常规流程推送
    }
}
```

行为边界：`@Transactional` 内发生的 publish / ACK / 上行持久化自动加入当前事务；
Linkora 后台编排线程（重试扫描、SSE 推送）始终在事务外运行，不受影响；
表结构迁移在原始 DataSource 上于参与事务前执行。

### 场景二：Spring MVC + JPA + MySQL 服务端

与 Boot 的差异只在装配入口，事务原理完全相同。JPA 配置类（项目里已有）：

```java
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "com.example.store.repo")
public class JpaConfig {

    @Bean
    public HikariDataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/store?useUnicode=true&characterEncoding=utf8");
        ds.setUsername("store");
        ds.setPassword("xxx");
        return ds;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(HikariDataSource dataSource) {
        /* Hibernate + MySQL，照你项目现有的配 */
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);   // JPA 事务管理器
    }
}
```

linkora 配置类与场景一**逐字相同**（四个 SPI bean 传 `TransactionAwareDataSourceProxy`），
只是它和 `JpaConfig` 都在你的 `@Import` 清单里；端点注册用前文"模式二"的方式 A/B。
业务 Service 同样 `@Transactional` 里 `publish`，业务行与消息行由
`JpaTransactionManager` 一起提交/回滚。

### 场景三：客户端（Spring Boot）不想要 SQLite 文件，收发队列进 MySQL

客户端也是 Spring Boot 应用、运维要求队列与业务数据一样进 MySQL 统一管理时：

**第 1 步：在你的 MySQL 库里建 3 张表**（`lk_` 前缀字段结构与内置 SQLite 版一致）：

```sql
CREATE TABLE lk_outbox (
  message_id    VARCHAR(64) PRIMARY KEY,
  message_type  VARCHAR(255) NOT NULL,
  payload       BLOB NOT NULL,
  status        VARCHAR(32) NOT NULL,
  created_at    BIGINT NOT NULL,
  updated_at    BIGINT NOT NULL,
  retry_count   INT NOT NULL DEFAULT 0,
  next_retry_at BIGINT,
  expires_at    BIGINT,
  last_error    TEXT,
  KEY idx_outbox_status_retry (status, next_retry_at),
  KEY idx_outbox_cleanup (status, updated_at)
);

CREATE TABLE lk_inbox (
  message_id     VARCHAR(64) PRIMARY KEY,
  message_type   VARCHAR(255) NOT NULL,
  payload        BLOB NOT NULL,
  receive_status VARCHAR(32) NOT NULL,
  process_status VARCHAR(32) NOT NULL,
  received_at    BIGINT NOT NULL,
  processed_at   BIGINT,
  retry_count    INT NOT NULL DEFAULT 0,
  next_retry_at  BIGINT,
  last_error     TEXT,
  KEY idx_inbox_recv_ack (receive_status, received_at),
  KEY idx_inbox_proc_retry (process_status, next_retry_at),
  KEY idx_inbox_cleanup (receive_status, process_status)
);

CREATE TABLE lk_meta (
  meta_key   VARCHAR(128) PRIMARY KEY,
  meta_value TEXT NOT NULL
);
```

**第 2 步：实现 `ClientStore` 接口**（14 个方法）。两个必须遵守的语义：
状态更新是 **CAS**（`UPDATE ... WHERE status = expected`，返回是否真的更新了行——
崩溃恢复与并发去重的根基）；幂等写入用"主键唯一约束 + 捕获重复键"。骨架与两个
关键方法（其余 12 个方法按同样模式写，SQL 逐字参考 `examples/tx-client` 的
`H2ClientStore`，换 MySQL 方言即可）：

```java
public class MySqlClientStore implements ClientStore {

    private final DataSource dataSource;

    public MySqlClientStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public boolean saveOutbox(OutboxEntry entry) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO lk_outbox (message_id, message_type, payload, status, "
                             + "created_at, updated_at, retry_count, next_retry_at, expires_at, last_error) "
                             + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, entry.getMessageId());
            ps.setString(2, entry.getMessageType());
            ps.setBytes(3, entry.getPayload());
            ps.setString(4, entry.getStatus().name());
            ps.setLong(5, entry.getCreatedAt());
            ps.setLong(6, entry.getUpdatedAt());
            ps.setInt(7, entry.getRetryCount());
            setNullableLong(ps, 8, entry.getNextRetryAt());
            setNullableLong(ps, 9, entry.getExpiresAt());
            setNullableString(ps, 10, entry.getLastError());
            return ps.executeUpdate() == 1;
        } catch (SQLIntegrityConstraintViolationException duplicate) {
            return false;   // messageId 已存在：幂等语义，保留原记录
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR, "saveOutbox 失败", e);
        }
    }

    @Override
    public boolean updateOutboxStatus(OutboxEntry updated, OutboxStatus expected) {
        // CAS：WHERE 里必须带期望状态，去掉会产生重复发送/重复回调
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE lk_outbox SET status = ?, updated_at = ?, retry_count = ?, "
                             + "next_retry_at = ?, last_error = ? "
                             + "WHERE message_id = ? AND status = ?")) {
            ps.setString(1, updated.getStatus().name());
            ps.setLong(2, updated.getUpdatedAt());
            ps.setInt(3, updated.getRetryCount());
            setNullableLong(ps, 4, updated.getNextRetryAt());
            setNullableString(ps, 5, updated.getLastError());
            ps.setString(6, updated.getMessageId());
            ps.setString(7, expected.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new LinkoraException(LinkoraErrorCode.STORAGE_ERROR, "updateOutboxStatus 失败", e);
        }
    }

    // 其余 12 个方法：findOutbox / findInbox / updateInboxReceiveStatus /
    // updateInboxProcessStatus / saveMeta / getMeta / delete... 同样模式
    // （setNullableLong / setNullableString 为普通的 null 判空 + setNull/setLong 辅助方法，此处省略）
}
```

**第 3 步：装配**——声明即生效，starter 发现你提供了 `ClientStore` 自动放弃内置 SQLite
（`db-path` 配置随之失效）：

```java
@Bean
public ClientStore clientStore(DataSource dataSource) {
    return new MySqlClientStore(dataSource);
}
```

MVC 宿主（`@Import(LinkoraClientConfiguration.class)`）同理，但自定义 bean 需加 `@Primary`。
客户端业务代码完全不变（`MessageListener` / `UplinkPublisher` 照旧）；若业务方法在
`@Transactional` 内调 `publish`，outbox 写入同样随宿主事务一起提交/回滚。

**服务端完全自实现**（连内置 JDBC 实现也不要）：实现 `ClientRegistry` / `MessageRepository` /
`DeliveryRepository` / `ClientCursorRepository` 四个接口、声明为 bean 即可，表结构完全由你
管理。必须遵守（内置实现已按此契约写好，仅自实现者需要照做）——仓储方法要**判断 JDBC
连接的 autoCommit 状态**（`java.sql.Connection` 的属性，不是 Linkora 配置）来决定谁负责提交：

```java
try (Connection c = dataSource.getConnection()) {
    boolean ownTx = c.getAutoCommit();  // true = 当前没有外部事务（如 Linkora 后台扫描线程调用）
    // ... 执行 SQL ...
    if (ownTx) {
        c.commit();     // 事务外：方法内自行提交
    }
    // autoCommit=false 说明正处在宿主 @Transactional 内：只执行 SQL，
    // 绝不能自己 commit/rollback，由 Spring 在事务边界统一提交/回滚——
    // 否则业务回滚时消息行已被你提交，发件箱语义被破坏
}
```

---

## 使用前必读

**三条铁律（违反会产生故障）**

1. **投递语义是至少一次**：同一条消息可能被重复送达，业务处理必须幂等（用消息 ID 去重）。
2. **下行消息必须显式 ACK**：处理完调用 `message.ackProcessed()`（失败调 `ackFailed(...)`），
   不 ACK 服务端会按策略无限重试。
3. **生产必须配 HMAC 认证**：不提供 `ClientAuthenticator` 时服务端**直接启动失败**
   （不会静默放行）。本地开发/演示需显式设置 `linkora.server.allow-anonymous=true`，
   启动日志会留痕；该开关绝不应出现在生产配置里。客户端侧把 `credentialProvider` 换成
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

`examples/` 目录下每个场景都有开箱即跑的工程（Boot / MVC 双端、宿主事务接管），
工程结构即推荐接入结构，直接参考或复制：

```bash
mvn install -DskipTests

# Spring Boot：终端 1 起服务端（8082），终端 2 起客户端（自动跑四向闭环输出 [PASS]）
mvn -pl examples/spring-boot-server/server-app spring-boot:run
mvn -pl examples/spring-boot-server/client-app spring-boot:run

# 服务端起来后，管理接口演示端点可直接体验（见"管理接口"章节）：
#   GET  http://127.0.0.1:8082/api/admin/clients          客户端概览
#   GET  http://127.0.0.1:8082/api/admin/unfinished?clientId=demo-client
#   GET  http://127.0.0.1:8082/api/admin/dead-letters?clientId=demo-client
#   GET  http://127.0.0.1:8082/api/admin/messages?clientId=demo-client
#   GET  http://127.0.0.1:8082/api/admin/stats?days=7
#   POST http://127.0.0.1:8082/api/admin/disconnect?clientId=demo-client
#   POST http://127.0.0.1:8082/api/admin/skip?clientId=demo-client&messageId=...

# Spring MVC：终端 1 起服务端（8081），终端 2 起客户端
mvn -pl examples/spring-mvc-server/server-app compile exec:java "-Dexec.mainClass=com.linkora.example.springmvc.SpringMvcServerMain" "-Dexec.args=8081"
mvn -pl examples/spring-mvc-server/client-app compile exec:java "-Dexec.mainClass=com.linkora.example.mvcclient.ClientDemoApplication"
```

## 构建

JDK 8 + Maven 3.6+：`mvn clean test`（全部自动化测试，无需外部中间件）。
