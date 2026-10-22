# Spring Boot 示例：线上平台 ⇄ 线下门店

演示 Linkora 在 Spring Boot 宿主下的完整业务化用法：`linkora.client.*` 与
`DataSource` 由 starter 全自动装配，业务代码采用标准 controller/service 分层，
每类消息一个独立处理文件。消息处理链路详解见
[docs/message-handling.md](../../docs/message-handling.md)。

## 业务场景与消息

| 方向 | 消息 topic | 服务端（线上平台） | 客户端（线下门店） |
|---|---|---|---|
| 线上下单 → 通知线下 | `order-created` | `service/OrderService` 发布 | `handler/OrderCreatedHandler` 处理 |
| 线上评价 → 通知线下 | `review-created` | `service/ReviewService` 发布 | `handler/ReviewCreatedHandler` 处理 |
| 线下改订单状态 → 线上 | `order-status-updated` | `service/uplink/OrderStatusHandler` 处理 | `service/OrderStatusService` 发送 |
| 线下回复评价 → 线上 | `review-reply` | `service/uplink/ReviewReplyHandler` 处理 | `service/ReviewReplyService` 发送 |

## 服务端结构（server-app）

```
com.linkora.example.boot
├── SpringBootServerApplication   # 启动类，LinkoraServer 由 starter 自动装配
├── controller
│   └── PlatformController        # HTTP 层：参数接收、调 service、组装响应
└── service
    ├── OrderService              # 下行：组装订单消息 → linkoraServer.publish
    ├── ReviewService             # 下行：组装评价消息 → linkoraServer.publish
    └── uplink
        ├── OrderStatusHandler    # 处理订单状态回传（继承 SDK 的 JsonUplinkHandler<T>），内存记录供查询
        └── ReviewReplyHandler    # 处理评价回复（继承 SDK 的 JsonUplinkHandler<T>），内存记录供查询
```

上行接线是全自动的：starter 自动收集容器里全部 `UplinkHandler` bean
（SDK 接口 `com.linkora.core.uplink.UplinkHandler`，两个方法：messageType + handle；
推荐直接继承 `JsonUplinkHandler<T>`，payload 自动反序列化为业务 DTO），
经内置路由器按 messageType 分发——**新增一种上行消息 = 新增一个 handler 类，
零装配代码**。

## 客户端结构（client-app）

```
com.linkora.example.bootclient
├── ClientDemoApplication         # 入口：只串演示流程，LinkoraClient 由 starter 装配
├── handler
│   ├── OrderCreatedHandler       # 下行：新订单通知 → 打印 → ackProcessed
│   └── ReviewCreatedHandler      # 下行：新评价通知 → 打印 → ackProcessed
└── service
    ├── OrderStatusService        # 上行：改订单状态 → UplinkPublisher 发布
    └── ReviewReplyService        # 上行：回复评价 → UplinkPublisher 发布
```

上行发送直接用 starter 自动注册的 `UplinkPublisher` bean（SDK 类，
封装"写本地队列发布 + 等待服务端确认"），业务 service 只负责组装 payload。

## 运行（仓库根目录，首次先 `mvn install -DskipTests`）

```bash
# 终端 1：服务端（8082）
mvn -pl examples/spring-boot-server/server-app spring-boot:run
# 终端 2：客户端（自动跑完四向闭环，输出 [PASS]）
mvn -pl examples/spring-boot-server/client-app spring-boot:run
```

## 接口

| 接口 | 说明 |
|---|---|
| `POST /api/orders?clientId=&orderNo=&item=` | 线上下单 → 通知线下门店 |
| `POST /api/reviews?clientId=&reviewId=&content=` | 线上评价 → 通知线下门店 |
| `GET /api/uplink/order-status-updates` | 查看线下回传的订单状态 |
| `GET /api/uplink/review-replies` | 查看线下回复的评价 |

框架内置端点 `/messages`、`/acks`、`/events` 由 starter 自动注册，业务代码无需关心。
