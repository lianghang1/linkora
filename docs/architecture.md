# 架构设计

> 0.6.0：按角色拆包后的模块结构。宿主只依赖自己角色的装配包，
> 服务端类不会出现在客户端 classpath（反之亦然）。

## 模块划分

```
内核（纯 Java，无 Spring）
├── linkora-core        消息模型 / 状态机 / 存储 SPI / 存储基建
│                       （EmbeddedSqliteDatabase、SchemaMigrator、
│                        JdbcClientStore、PayloadCipher、sync 快照表）
├── linkora-client      客户端编排：调度、ACK、离线补发、sync 工具
└── linkora-server      服务端编排：路由、投递、SSE、认证、指令通道

Spring 装配层（按角色拆分，宿主入口）
├── linkora-spring-common                 两端共用：事务感知数据源包装
├── linkora-client-spring                 MVC 客户端装配
├── linkora-server-spring                 MVC 服务端装配
├── linkora-client-spring-boot-starter    Boot 客户端自动装配
└── linkora-server-spring-boot-starter    Boot 服务端自动装配

可选
├── linkora-admin-console   运维控制台（仅服务端角色，optional 传递）
└── linkora-testkit         测试基建（不发布）
```

## 依赖方向

```
core ← client ← client-spring ← client-spring-boot-starter
core ← server ← server-spring ← server-spring-boot-starter → (optional) admin-console
core ← spring-common ← client-spring / server-spring
```

## 模块职责

| 模块 | 职责 |
|---|---|
| core | 消息信封与编解码、两端口径的状态机、存储 SPI 与内置实现（SQLite/JDBC）、传输加密、数据同步纯函数 |
| client | 发送/恢复调度、SSE 订阅、ACK 协调、离线缓冲、顺序键门禁、DataSync 门面 |
| server | 路由与投递、SSE 推送（慢客户端隔离）、上行摄入与路由、死信/告警、管理接口 |
| 装配层 | 条件装配、属性绑定、事务感知包装、认证模式解析 |

## 关键技术决策

| 决策 | 理由 |
|---|---|
| 存储引擎统一 SQLite（内嵌模式） | 两端同引擎：测试即生产行为；容量论证见 storage.md |
| SSE 下行 + HTTPS POST 上行 | 服务端单向推送无需客户端可达；防火墙只开出站 |
| 纯应用层 HMAC/Token 认证 | 不依赖 mTLS/PKI，线下设备证书管理成本为零 |
| 传输加密在 SDK 层（非 TLS 依赖） | 部署环境无关；HTTP 也有机密性 |
| 存储按角色拆包 | 客户端宿主 classpath 无服务端类（审计面、依赖纯净） |

## 线程与资源模型

- 服务端：HTTP 线程（摄入）+ 投递调度线程 + SSE 异步写（每连接有界队列，
  慢客户端不阻塞他人）+ 后台扫描线程
- 客户端：worker 单线程按序分发（顺序键语义）+ 发送/恢复调度线程 + SSE 读线程
- SQLite：WAL 模式，读并发、写串行（busy_timeout 排队），容量论证见 storage.md
