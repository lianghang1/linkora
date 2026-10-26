# 0.5.0 → 0.6.0 迁移指南

> 0.6.0 是破坏性变更（拆包 + 传输加密默认开启）。按本表替换依赖、补两处配置即可。

## 一、依赖替换（全部改名，一次替换完成）

| 0.5.0（已发布，不再维护） | 0.6.0 | 说明 |
|---|---|---|
| `linkora-spring-boot-starter`（服务端宿主） | `linkora-server-spring-boot-starter` | 含服务端装配 + 可选控制台 |
| `linkora-spring-boot-starter`（客户端宿主） | `linkora-client-spring-boot-starter` | 引包即用，无需 enabled=true |
| `linkora-spring`（MVC 服务端宿主） | `linkora-server-spring` | @Import(LinkoraServerConfiguration) 不变 |
| `linkora-spring`（MVC 客户端宿主） | `linkora-client-spring` | @Import(LinkoraClientConfiguration) 不变 |

新结构：`core / client / server` 内核不变；新增 `linkora-spring-common`（两端共用事务包装，
传递依赖自动带入，无需显式声明）；`linkora-admin-console` 仍为可选 jar（仅服务端角色）。

## 二、必配：传输加密密钥（默认开启）

0.6.0 起 payload 传输加密**默认开启**（AES-256-GCM，与认证方式无关），服务端与客户端
必须配置**相同的** 32 字节密钥，缺失时启动即失败：

```yaml
linkora:
  payload-cipher-key: "Qr1J5BCqFYkIlxfELSx6xYoZZ5YxYEZyWHZrBOJT83M="  # openssl rand -base64 32
  # 如确不需要传输加密（例如纯内网且已自评估），显式关闭：
  # payload-cipher: false
```

- 线格式：信封新增 `"enc": "A256GCM:k1"` 字段，payload 为 `Base64(IV[12]+密文+Tag[16])`
- 与认证完全解耦：HMAC / Token / 匿名部署均可启用
- 只保护传输（客户端加密、服务端解密后按既有流程落库），落库为明文
- 混版本升级注意：两端须同时升到 0.6.0 并配 key——0.5.0 客户端发往 0.6.0 服务端
  的消息无 enc 标记（明文）可正常处理；0.6.0 客户端发往 0.5.0 服务端会被忽略
- 大 payload（>64KB）自动 gzip（加密前压缩），服务端透明解压

## 三、认证模式（新增，可选）

```yaml
# 服务端：linkora.server.auth-mode = hmac（默认）| token | anonymous
linkora:
  server:
    auth-mode: token        # token = 委托宿主账号体系校验

# token 模式下宿主提供校验器 Bean（回调宿主已有的登录/会话体系）：
# @Bean TokenValidator validator() { return (clientId, token) -> ...; }

# 客户端：linkora.client.auth-mode = hmac（默认）| token | none
linkora:
  client:
    auth-mode: token
    auth-token: "宿主登录体系下发的 token"   # 静态演示；
    # 生产实现 com.linkora.client.auth.TokenProvider Bean（每次请求取最新 token，自动续期）
```

## 四、引包即用语义变化

- 客户端 starter 的 `linkora.client.enabled` 默认值 false → **true**（引包即装配）；
  显式关闭仍支持
- 客户端-only 宿主**不再需要** `linkora.server.enabled=false`（服务端类不在 classpath）
- `linkora.server.enabled` 默认值不变（true）

## 五、数据同步工具（新增，可选）

`com.linkora.client.sync.DataSync`：面向"业务系统不改造、旁挂客户端只读源库定时上报"
场景——全量读取 + 客户端快照 diff（快照表随客户端存储走）+ 只发增量 +
整表指纹对账 + 大变更集自动分片。见 examples（墓位数据同步演示）与
`com.linkora.client.sync` 包 Javadoc。

## 六、升级步骤清单

1. 全部两端宿主同时升级到 0.6.0（协议新增 enc 字段，旧端忽略但不解密）
2. 按第一节替换依赖坐标
3. 生成密钥并写入两端配置（第二节）
4. 如使用 token 认证：服务端实现 TokenValidator、客户端配置 auth-mode=token（第三节）
5. 重启（先服务端后客户端；轮换窗口内未送达消息由 at-least-once 自动补投）
