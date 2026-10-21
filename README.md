# Linkora

面向线上服务与多个线下 Java 客户端的可靠双端通信 SDK。

- 最低 JDK：8（构建与测试在真实 JDK 8 上执行）
- 通信方案：SSE 下行 + HTTPS POST 上行
- 可靠性：服务端持久化 + 客户端独立 SQLite + 接收/处理两级 ACK + 幂等 + 重试 + 断线补发
- 投递语义：至少一次（at-least-once），业务侧需自行保证副作用幂等

详细设计见 `Linkora_最终开发方案.md` 与 `docs/`。

## 模块（随开发阶段逐步增加）

| 模块 | 职责 | 状态 |
|---|---|---|
| linkora-core | 消息模型、状态机、错误码、序列化抽象、存储 SPI | 阶段 1 开发中 |
| linkora-client / linkora-server / 存储实现 / 传输实现 / Spring 集成 | 见方案第 3 节 | 后续阶段 |

## 构建

要求 JDK 8 + Maven 3.6+。

```bash
mvn clean test
```
