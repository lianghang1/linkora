# 兼容性说明

## JDK

- **最低要求 JDK 8**；全程使用真实 JDK 1.8.0_341 构建与测试通过（非仅
  `source/target=8` 编译参数声明）；
- 未使用任何 JDK 9+ API（无 `java.net.http.HttpClient`、无 `var`、无
  List.of 等）；测试矩阵另覆盖较新 JDK 编译时请保持 `-source/-target 8`。

## 运行环境矩阵

| 环境 | 要求 | 状态 |
|---|---|---|
| 客户端运行时 | JDK 8+，纯 Java，无 Spring 依赖 | 已验证 |
| 服务端容器 | Servlet 3.1+（需要 async 能力）：Tomcat 8+/9+、Jetty 9.3+、WildFly 等 | Jetty 9.4 测试/示例验证通过 |
| `javax.servlet-api` | 3.1.0，宿主容器 provided | 3.1 API 面（无 Servlet 4.0 常量依赖） |
| 生产数据库 | 任一 ANSI SQL 兼容 JDBC 库 | 见下文数据库矩阵 |
| 消息中间件 | **不要求**，服务端零新增中间件 | 设计保证 |

## 依赖矩阵（JDK 8 兼容性均已核实）

| 依赖 | 版本 | 冲突规避约定 |
|---|---|---|
| okhttp / okhttp-sse | 4.12.0 | 传递引入 kotlin-stdlib（约 1.7MB）；宿主如已有 OkHttp 4.x 可通过 `OkHttpTransport.builder().client(...)` 复用外部实例 |
| jackson-databind | 2.17.2 | 代码只使用 2.13+ 稳定 API，宿主版本仲裁差异（2.13~2.17+）不影响功能 |
| sqlite-jdbc | 3.46.1.3 | **宿主 classpath 必须保证版本唯一**：多版本共存会导致 native 库重复加载冲突（`NoClassDefFoundError: Could not initialize class org.sqlite.NativeDB` 类报错） |
| slf4j-api | 1.7.36 | 仅门面；日志实现完全由宿主提供（缺实现时退化为 NOP，不报错） |
| h2 | 2.2.224 | 服务端**测试**依赖；生产用业务库，不会进入宿主 classpath |
| jetty 9.4.x | 9.4.56 | 仅 testkit 与 examples 使用，生产服务端走 Servlet 适配器 |
| junit 4.13.2 | test | 不进入主依赖 |

模块隔离说明：冲突一律通过模块边界控制影响范围，**不使用 shade 打包**。

## 数据库矩阵（服务端持久化）

| 数据库 | 状态 | 说明 |
|---|---|---|
| H2 2.2.x | **已验证**（全部自动化测试 + testkit） | SQL 为方言中立 ANSI |
| MySQL 8 | 代码就绪，**待真实库回归** | 接入前在测试库执行 `JdbcServerSchema.migrate` 验证；关注 `TEXT` 类型与索引长度限制 |
| PostgreSQL | 代码就绪，**待真实库回归** | 同上 |

服务端存储通过 Repository 抽象隔离，新库接入只需提供 `DataSource`，
无需改代码。

## 客户端本地存储

- SQLite（xerial sqlite-jdbc）：Windows/Linux/macOS x86_64 / aarch64
  官方支持平台；
- 配置 `WAL` + `synchronous=FULL`：进程崩溃/断电场景下已提交事务不丢失；
- 客户端每个实例独占一个库文件。

## Spring 集成

当前版本提供**普通 Java 全链路**（客户端零 Spring、服务端 Servlet 适配器
兼容 Spring MVC 宿主）；`linkora-spring` / `linkora-spring-boot-starter`
模块与 Spring MVC / Boot 专属示例**尚未提供**，宿主以 Spring MVC 运行时
可直接按 deployment.md 的方式注册三个 Servlet，无需 Starter。

## 协议兼容性

- 协议版本头 `X-Linkora-Protocol: 1`；服务端对不匹配版本返回 412；
- JSON 信封字段（payloadBase64 等）与 ACK 结构见 protocol.md；
- schema_version 字段随消息持久化，未来协议演进按版本区分处理。
