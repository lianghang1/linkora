# Spring MVC 示例：线上平台 ⇄ 线下门店

与 [spring-boot-server](../spring-boot-server/README.md) 相同的业务场景与分层结构，
差异在于装配方式：服务端与客户端都是 Spring MVC 注解容器宿主（`linkora-spring`
装配，不依赖 Spring Boot）——服务端装配默认生效，客户端通过
`@Import(LinkoraClientConfiguration.class)` 显式启用（配置键 `linkora.client.*`
与 Boot 版完全对齐）。消息处理链路详解见 [docs/message-handling.md](../../docs/message-handling.md)。

## 业务场景与消息

| 方向 | 消息 topic | 服务端（线上平台） | 客户端（线下门店） |
|---|---|---|---|
| 线上下单 → 通知线下 | `order-created` | `service/OrderService` 发布 | `handler/OrderCreatedHandler` 处理 |
| 线上评价 → 通知线下 | `review-created` | `service/ReviewService` 发布 | `handler/ReviewCreatedHandler` 处理 |
| 线下改订单状态 → 线上 | `order-status-updated` | `service/uplink/OrderStatusHandler` 处理 | `service/OrderStatusService` 发送 |
| 线下回复评价 → 线上 | `review-reply` | `service/uplink/ReviewReplyHandler` 处理 | `service/ReviewReplyService` 发送 |

## 服务端结构（server-app）

```
com.linkora.example.springmvc
├── SpringMvcServerMain           # 注解容器 + Jetty + LinkoraServlets 三端点注册
├── AppConfig                     # @ComponentScan + 数据源 + LinkoraServerConfiguration
├── controller
│   └── PlatformController        # HTTP 层：参数接收、调 service、组装响应
└── service
    ├── OrderService              # 下行：组装订单消息 → linkoraServer.publish
    ├── ReviewService             # 下行：组装评价消息 → linkoraServer.publish
    └── uplink
        ├── UplinkHandler         # 上行处理器接口（messageType + handle）
        ├── UplinkMessageDispatcher  # 实现 IngestListener（唯一框架入口），按类型转发
        ├── OrderStatusHandler    # 处理订单状态回传，内存记录供查询
        └── ReviewReplyHandler    # 处理评价回复，内存记录供查询
```

上行接线：`LinkoraServerConfiguration` 从容器中取 `IngestListener` bean 自动接入，
`UplinkMessageDispatcher` 再按 messageType 路由给各 `UplinkHandler` 实现。

## 客户端结构（client-app，引 linkora-spring 单包）

```
com.linkora.example.mvcclient
├── ClientDemoApplication         # 入口：@Import 客户端装配，串演示流程
├── handler
│   ├── OrderCreatedHandler       # 下行：新订单通知 → 打印 → ackProcessed
│   └── ReviewCreatedHandler      # 下行：新评价通知 → 打印 → ackProcessed
└── service
    ├── AbstractUplinkService     # 公共：outbox 发送 + SERVER_ACCEPTED 确认
    ├── OrderStatusService        # 上行：改订单状态 → 通知线上（@Service）
    └── ReviewReplyService        # 上行：回复评价 → 通知线上（@Service）
```

客户端装配：`LinkoraClient` 与 SQLite 本地队列由 linkora-spring 的
`LinkoraClientConfiguration` 创建并托管生命周期（start/close 随容器），
配置来自 `linkora-client.properties`，JVM 参数 `-Dlinkora.client.*` 可覆盖。

## 运行（仓库根目录，首次先 `mvn install -DskipTests`）

```bash
# 终端 1：服务端（8081）
mvn -pl examples/spring-mvc-server/server-app compile exec:java "-Dexec.mainClass=com.linkora.example.springmvc.SpringMvcServerMain" "-Dexec.args=8081"
# 终端 2：客户端（自动跑完四向闭环，输出 [PASS]）
mvn -pl examples/spring-mvc-server/client-app compile exec:java "-Dexec.mainClass=com.linkora.example.mvcclient.ClientDemoApplication"
```

## 接口

| 接口 | 说明 |
|---|---|
| `POST /api/orders?clientId=&orderNo=&item=` | 线上下单 → 通知线下门店 |
| `POST /api/reviews?clientId=&reviewId=&content=` | 线上评价 → 通知线下门店 |
| `GET /api/uplink/order-status-updates` | 查看线下回传的订单状态 |
| `GET /api/uplink/review-replies` | 查看线下回复的评价 |

HMAC 认证接入见客户端 `ClientDemoApplication` 类注释与 [docs/security.md](../../docs/security.md)。
