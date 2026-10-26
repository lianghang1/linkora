# Linkora 示例（0.6.0 殡葬集团业务场景）

四个全功能示例应用（均为 Tomcat），模拟真实接入形态：**集团总部线上服务 +
门店/墓园线下终端，业务系统已存在，接入 linkora 实现双向可靠通信**。

| 应用 | 角色 | 宿主 | 演示内容 |
|---|---|---|---|
| `boot-server` | 总部服务端 | Spring Boot（内嵌 Tomcat） | 订单下发（顺序键）、墓位数据同步接收、token 认证（回调宿主校验）、控制台、传输加密 |
| `boot-client` | 墓园终端 | Spring Boot（内嵌 Tomcat） | 订单接收处理、状态回传（上行+ACK）、墓位数据同步（只读业务库+快照 diff+增量上报）、token 携带、离线补发 |
| `mvc-server` | 总部服务端 | Spring MVC（Tomcat） | 同 boot-server（手动装配形态） |
| `mvc-client` | 门店终端 | Spring MVC（Tomcat） | 同 boot-client（手动装配形态） |

每个示例均为两种存储模式（`store=sqlite` 内嵌默认 / `store=datasource` 宿主库同事务）。

## 认证演示（token 模式）

- 服务端 `HostTokenValidator`：回调宿主账号体系校验（演示约定 `grp-<clientId>-<日期>`）
- 客户端静态 token（`linkora.client.auth-token`）；生产实现
  `TokenProvider` Bean 动态获取（每次请求自动携带最新 token）
- 错误 token → 服务端 401 拒绝（可自行 curl 验证）

## 传输加密

两端配置相同 `linkora.payload-cipher-key`（默认开启）；线路上的 payload 为
AES-256-GCM 密文，服务端解密后进业务逻辑。抓包可见内容均为密文。

## 数据同步（墓位场景）

```bash
# 1. 启动 boot-server（8082）与 boot-client（8083）
# 2. 模拟业务系统写入墓位（业务系统不改造，数据进"它自己的库"）
curl -X POST "http://127.0.0.1:8083/api/business/tomb" -d "code=A-001&name=测试墓位"
# 3. 等 30s 定时同步：客户端日志显示"全量读取 N 行，增量 M"——只上报变更
# 4. 服务端 TombSyncHandler 按业务主键 upsert；再改一行 → 下轮只报 1 条 UPDATE
```

各应用启动方式见各自 README；迁移与依赖对照见 docs/migration-0.6.0.md。
