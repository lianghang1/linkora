# 架构设计

## 模块划分与依赖方向

```
                ┌──────────────────────────────────────────┐
                │              linkora-parent              │
                └──────────────────────────────────────────┘
                                     │
        ┌────────────────────────────┼─────────────────────────────┐
        │                            │                             │
  linkora-core               linkora-client                linkora-server
  （消息模型/状态机/SPI）        （生命周期/调度/ACK 协调）      （路由/投递/状态协调）
        ▲                            │                             │
        │                    ┌───────┴────────┐            ┌──────┴────────┐
        │                    │                │            │               │
        │          linkora-client-storage  linkora-     linkora-server-  linkora-
        │          -sqlite（独立 SQLite）   transport    storage-jdbc     servlet
        │                    │           -sse/-http          │
        │                    └───────┬────────┘            │
        │                            │                     │
        │                     OkHttp 4.x            JDBC（H2/MySQL/PostgreSQL）
        │
  可选模块（不反向依赖核心）：linkora-spring、linkora-spring-boot-starter、
  linkora-testkit、examples/*
```

依赖规则（P0 约束）：

1. `linkora-core` 零第三方运行时依赖（仅测试期 JUnit），只含纯 Java。
2. 核心模块（core/client/server）不得依赖 Spring、Servlet、具体数据库驱动。
3. Spring 集成与存储实现是单向依赖核心的上层模块，核心对它们无感知。
4. 传输层统一使用 OkHttp 4.x 引擎，对外保留 SPI 允许替换。

## 模块职责

| 模块 | 职责 |
|---|---|
| linkora-core | 消息模型、状态机、错误码、ACK 语义枚举、序列化抽象、服务端/客户端存储 SPI、Jackson 默认序列化（LinkoraJsonUtil） |
| linkora-client | 客户端生命周期、OkHttp 传输（SSE 接收 + POST 上行）、outbox 发送调度、ACK 流程、重连退避、SQLite 本地存储（lk_outbox/lk_inbox/lk_meta） |
| linkora-server | 消息路由、多客户端管理、投递状态协调、补发、死信管理、JDBC 持久化（消息正文 + 投递状态分离）与 Servlet async 输出适配 |
| linkora-spring / starter | 可选集成，不强制 |
| linkora-testkit | 嵌入式测试服务端（Jetty 9.4）、故障注入工具 |

## 关键技术决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 通信 | SSE 下行 + HTTPS POST 上行 | 只依赖标准 HTTP，复用宿主已有基础设施，零新增可部署组件 |
| HTTP 客户端 | OkHttp 4.12+（含 okhttp-sse） | JDK 8 兼容、活跃维护；3.14 已 EOL 弃用 |
| JSON | Jackson 2.17.x，只用 2.13+ 稳定 API | 降低宿主版本仲裁冲突风险 |
| SQLite | xerial sqlite-jdbc 3.4x | 宿主 classpath 必须版本唯一（native 库冲突） |
| 服务端 SSE 输出 | Servlet 3.1 异步非阻塞写（WriteListener + 每连接有界出站队列） | 慢客户端隔离；非阻塞 I/O 由容器内核（Tomcat/Jetty）承担，装配层贴官方示例骨架 |
| schema 迁移 | 自研版本表 + 顺序脚本 | 不引入 Flyway，迁移逻辑量小 |
| 指标 | 核心 SPI + 可选 Micrometer 适配 | 不强制监控框架 |
| 安全 | 纯应用层 token + HMAC，不要求 PKI/证书 | 生产环境可能无法满足证书管理要求 |

## 线程与资源模型

- 所有线程池/调度器有界、命名（`linkora-*` 前缀）、可配置。
- 每连接推送出站队列按字节有界（默认 8MB）；队列满主动断开该 SSE 连接，靠重连补发恢复，绝不无限缓冲、绝不丢未确认消息。
- 慢监听器只延迟本客户端的后续消息处理；业务回调不在数据库锁内执行。
- `close()` 语义：停止接收新请求 → 等待在途操作 → 关闭连接与线程池 → 释放资源。
