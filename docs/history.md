# 版本历史与迁移（History & Migration）

> 本篇汇总所有历史版本的破坏性变更与升级步骤；新版本接入直接看
> [getting-started.md](getting-started.md) 即可，本篇仅在升级时阅读。

## 0.6.0（当前版本）

### 变更总览

| # | 变更 | 类型 |
|---|---|---|
| 1 | **按角色拆包**（官方 starter 命名规范），统一包废弃 | 破坏性 |
| 2 | **传输加密默认开启**（AES-256-GCM，两端须配相同密钥） | 破坏性 |
| 3 | 认证扩展：token 模式（回调宿主账号体系）+ 客户端 TokenProvider SPI | 新增 |
| 4 | 数据同步工具 `DataSync`（快照 diff + 增量上报，快照表进客户端 schema v3） | 新增 |
| 5 | 大 payload（>64KB）自动 gzip（加密前压缩） | 新增 |
| 6 | 客户端 starter `enabled` 默认值 false → true（引包即用） | 行为变更 |

### 升级步骤

1. **依赖替换**（全部改名）：

| 0.5.0 | 0.6.0 |
|---|---|
| `linkora-spring-boot-starter`（服务端宿主） | `linkora-server-spring-boot-starter` |
| `linkora-spring-boot-starter`（客户端宿主） | `linkora-client-spring-boot-starter` |
| `linkora-spring`（MVC 服务端宿主） | `linkora-server-spring` |
| `linkora-spring`（MVC 客户端宿主） | `linkora-client-spring` |

2. **配置传输加密**（两端一致，缺失启动失败）：

```yaml
linkora:
  payload-cipher-key: "<openssl rand -base64 32>"
  # payload-cipher: false   # 如确不需要传输加密，显式关闭
```

3. **（可选）启用 token 认证**：服务端 `auth-mode: token` + 实现 `TokenValidator`
   Bean；客户端 `auth-mode: token` + 静态 token 或 `TokenProvider` Bean

4. **两端同时升级**：0.5.0 客户端 → 0.6.0 服务端可正常收（明文无 enc 标记）；
   反向（0.6.0 客户端 → 0.5.0 服务端）密文会被忽略，须避免

5. 重启顺序：先服务端后客户端；窗口内未送达消息由 at-least-once 自动补投

### 行为语义说明（无迁移动作，但需知晓）

- 客户端-only 宿主不再需要 `linkora.server.enabled=false`（服务端类不在 classpath）
- 加密消息在控制台中显示为密文（预期）
- 混版本窗口：升级期间旧客户端的明文消息照常处理

---

## 0.5.0（已发布）

### 变更总览

| # | 变更 | 类型 |
|---|---|---|
| 1 | 两端存储模型对等：`store=sqlite`（内嵌，**服务端默认值变更**）/ `store=datasource`（宿主库同事务） | 破坏性 |
| 2 | 定位收窄：仅支持 Spring MVC(Tomcat) 与 Spring Boot(Tomcat) 宿主 | 声明 |
| 3 | 示例重组为 boot/mvc 四应用；testkit 移至仓库顶层 | 结构 |
| 4 | Boot starter 暴露死信告警配置（`alert-webhook-*` / `inbox-stuck-minutes`） | 新增 |

### 升级步骤

1. **服务端默认存储变更**：0.4.x 复用宿主 DataSource；0.5.0 起默认内嵌 SQLite
   （历史数据仍在原库但不被读取）。保留原行为需显式：

```yaml
linkora:
  server:
    store: datasource
```

   历史数据迁移：不做自动搬迁工具，按需手工执行 SQL（同 schema，直接 INSERT SELECT）。

2. **升级注意事项**：
   - 升级前备份消息库
   - `datasource` 模式的 MySQL 用户需保留原权限；内嵌模式自动创建 `./linkora/` 目录
   - 客户端 0.4.x 升 0.5.0 无 schema 变更（客户端表结构未变）

---

## 0.4.x 及更早

| 版本 | 要点 |
|---|---|
| 0.4.x | 顺序键（sequenceKey）、消息过期（expiresAt/traceId）、死信运营接口、客户端死信指令通道、运维控制台（0.4.0 引入，独立可选 jar） |
| 0.3.x | 两级 ACK、服务端投递状态机、慢客户端隔离 |
| 0.1–0.2 | 初始可靠双端通信、HMAC 认证、内嵌 SQLite 客户端队列 |

0.4.x → 0.5.0 的完整变更清单见对应版本 GitHub 提交记录（`891a37e` 起）。
