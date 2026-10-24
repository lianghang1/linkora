# 自定义存储与事务接管

内置存储开箱即用（服务端复用宿主 DataSource 的 JDBC 实现；客户端 SQLite），
两端都支持替换为自己的实现。本文按真实项目形态给出完整接法，
配套可运行示例：`examples/spring-tx-server`（服务端事务接管）与 `examples/tx-client`（客户端 MySQL 队列）。

存储 SPI 的完整接口契约见 [storage-spi.md](storage-spi.md)。

## 场景一：Spring Boot + JPA + MySQL 服务端——业务与消息同一事务（事务性发件箱）

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

新增一个配置类：四个存储 SPI 的连接取自**事务感知数据源**（包住 JPA 在用的那个
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
        // 后续任意一步抛异常 → 订单行与消息一起回滚，客户端永远看不到"失败订单"的通知；
        // 正常返回 → 一起提交后按常规流程推送
    }
}
```

行为边界：

- `@Transactional` 内发生的 publish / ACK / 上行持久化自动加入当前事务；
- Linkora 后台编排线程（重试扫描、SSE 推送）始终在事务外运行，不受影响；
- 表结构迁移在原始 DataSource 上于参与事务前执行。

## 场景二：Spring MVC + JPA + MySQL 服务端

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
        /* Hibernate + MySQL，按项目现有配置 */
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
```

linkora 配置类与场景一相同（四个 SPI bean 传 `TransactionAwareDataSourceProxy`），
它与 `JpaConfig` 都在 `@Import` 清单里；端点注册见
[getting-started.md](getting-started.md) 的"MVC 服务端"一节。
业务 Service 同样在 `@Transactional` 里 `publish`，业务行与消息行由
`JpaTransactionManager` 一起提交/回滚。

## 场景三：客户端队列进 MySQL（不用 SQLite 文件）

客户端也是 Spring Boot 应用、运维要求上行/下行队列与业务数据统一进 MySQL 时：

### 1. 在 MySQL 库里建 3 张表

字段结构与内置 SQLite 版一致：

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

### 2. 实现 ClientStore 接口（14 个方法）

两个必须遵守的语义：

- **状态更新是 CAS**：`UPDATE ... WHERE status = expected`，返回是否真的更新了行——
  这是崩溃恢复与并发去重的根基；
- **幂等写入**：主键唯一约束 + 捕获重复键，返回 false 表示已存在（保留原记录）。

关键方法示例（其余方法按同样模式写，SQL 参考 `examples/tx-client` 的 `H2ClientStore`，
换 MySQL 方言即可）：

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

    // 其余方法：findOutbox / findInbox / updateInboxReceiveStatus /
    // updateInboxProcessStatus / saveMeta / getMeta / delete... 同样模式
}
```

### 3. 装配

声明即生效，starter 发现你提供了 `ClientStore` 自动放弃内置 SQLite（`db-path` 配置随之失效）：

```java
@Bean
public ClientStore clientStore(DataSource dataSource) {
    return new MySqlClientStore(dataSource);
}
```

MVC 宿主（`@Import(LinkoraClientConfiguration.class)`）同理，自定义 bean 需加 `@Primary`。
客户端业务代码完全不变（`MessageListener` / `UplinkPublisher` 照旧）；若业务方法在
`@Transactional` 内调 `publish`，outbox 写入同样随宿主事务一起提交/回滚。

## 场景四：服务端完全自实现

连内置 JDBC 实现也不要时：实现 `ClientRegistry` / `MessageRepository` /
`DeliveryRepository` / `ClientCursorRepository` 四个接口、声明为 bean 即可，
表结构完全由你管理。

必须遵守的事务契约（内置实现已按此写好，仅自实现者需要照做）——仓储方法要判断
JDBC 连接的 `autoCommit` 状态来决定谁负责提交：

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
