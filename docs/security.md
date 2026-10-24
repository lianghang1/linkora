# 安全设计

## 原则：纯应用层，不依赖 PKI/证书

真实生产环境可能无法满足证书管理要求，安全能力全部在 SDK 功能层实现：

- **不要求任何证书申请或 mTLS 配置**。
- HTTPS 为建议项：客户端保持 JDK 标准证书校验（禁止默认关闭），不要求用户申请证书。
- 内网 HTTP 环境可运行，风险由接入方评估（文档标注）。
- IP 级封禁、WAF、fail2ban 交给宿主已有反向代理层，SDK 提供配合配置说明。

## 缺省即拒绝（fail-fast）

缺少 `ClientAuthenticator` 时，装配层（`LinkoraServerAssembler`）**直接抛异常终止启动**，
而不是回退成"放行全部请求"。理由：SSE 端点通常直接暴露在网络上，一个配置漏项
就等于任何人都能订阅任意 clientId 的下行数据，而"启动成功"会让人误以为一切正常。

本地开发/演示需要匿名访问时显式打开：

```yaml
linkora:
  server:
    allow-anonymous: true   # 仅限本地；启动日志会打印醒目的 WARN
```

Spring MVC 宿主同样读取 `linkora.server.allow-anonymous`（默认 false）。

## 认证机制：HMAC 请求签名

### 请求签名算法

客户端与服务端共享唯一权威实现 [HmacSha256](../linkora-core/src/main/java/com/linkora/core/security/HmacSha256.java)：

```text
bodyHash   = SHA-256(body).hexLower          （body 为空时对空字节数组求哈希）
canonical  = clientId + "\n" + HTTP方法 + "\n" + timestampMillis + "\n" + nonce + "\n" + bodyHash
signature  = HMAC-SHA256(secretBytes, canonical).hexLower
```

设计说明：

- 签名**不包含 URL 路径**，避免 Servlet 容器对 contextPath/servletPath 的映射差异导致
  校验失败；跨端点重放由 timestamp + nonce 缓存拦截。
- 所有比较使用恒定时间比较（`MessageDigest.isEqual`），防时序侧信道。
- 纯 JDK crypto（`javax.crypto.Mac`），无新增依赖，JDK 8 兼容。

### 请求头

| 头名 | 说明 |
|---|---|
| `X-Linkora-Access-Token` | 服务端发放的高熵访问令牌（`HmacSha256.generateToken()` 生成 256 bit Base64URL） |
| `X-Linkora-Timestamp` | 请求发起时间（epoch 毫秒），服务端校验 ±5 分钟窗口（可配） |
| `X-Linkora-Nonce` | 一次性随机数（128 bit hex），服务端缓存窗口内 nonce 拒绝重放 |
| `X-Linkora-Signature` | HMAC-SHA256 签名（hex 小写） |

**令牌禁止放入 URL**（避免进入反代/访问日志），一律走请求头。

### 客户端接入

```java
LinkoraClient client = LinkoraClient.builder()
    .serverUrl("https://server.example.com/linkora")
    .clientId("store-001")
    .clientStore(store)
    .credentialProvider(new HmacCredentialProvider("store-001", accessToken, secretBytes))
    .build();
```

`HmacCredentialProvider` 线程安全；每个请求自动生成新 nonce 与签名；
`destroySecret()` 支持停用时清零内存密钥。

### 服务端接入

```java
CredentialStore store = ...; // 宿主实现：按 clientId 查找已发放的 token + secret
LinkoraServer server = LinkoraServer.builder()
    .clientAuthenticator(new HmacClientAuthenticator(store))
    // 可选：自定义时钟窗口 / 锁定策略 / nonce 缓存
    // new HmacClientAuthenticator(store, 300_000L, new FailureLockout(5, 2000, 900_000), new NonceCache(...))
    ...
    .build();
```

服务端三入口（SSE 订阅 `/events`、消息上报 `/messages`、ACK `/acks`）共用同一认证器；
认证失败统一回 401 + `AUTH_FAILED`，不区分"令牌错/签名错"，防枚举。

### 校验顺序

1. clientId 存在且未被锁定（锁定直接 429 + `RATE_LIMITED`）；
2. 凭据记录存在、令牌恒定时间匹配；
3. 时间戳在允许窗口内；
4. nonce 未出现过（防重放）；
5. HMAC 签名恒定时间匹配。

任一步失败计入失败锁定；全部通过即清零失败计数。

## 防爆破（SSE 接口暴露于互联网）

1. **高熵 token 使在线爆破在算力上不可行**（第一道防线，256 bit）。
2. **失败指数锁定**（[FailureLockout](../linkora-server/src/main/java/com/linkora/server/auth/FailureLockout.java)）：
   同一 clientId 连续失败 N 次（默认 5）后按 2^n 指数锁定（默认 2s 起步、封顶 15 分钟）。
   锁定期间直接拒绝、不校验凭据（拖慢爆破 + 节省服务端算力）。
   纯内存实现：重启即清零是可接受的降级（锁定的目的是拖慢爆破而非精确审计）。
3. **防枚举**：未知 clientId 与错误凭据返回统一错误码 `AUTH_FAILED`（401）。
4. **防重放**：时间戳窗口 + nonce 缓存（[NonceCache](../linkora-server/src/main/java/com/linkora/server/auth/NonceCache.java)，
   有界 10 万条，超限惰性驱逐，重启清零可接受——攻击者重启服务端无法获益）。
5. 认证失败计数建议接入宿主监控告警；连接数限制交给宿主容器配置。

## 凭据管理约定

- 凭据存储为可插拔 SPI（[CredentialStore](../linkora-server/src/main/java/com/linkora/server/auth/CredentialStore.java)），
  宿主自行决定落库/加密方式；SDK 不持久化明文凭据。
- 客户端 secret 以字节数组在内存持有；SDK 本地 SQLite 不保存凭据。
- 双凭据并存窗口（平滑轮换）：宿主在 `CredentialStore` 实现中同时接受新旧
  token/secret 即可，SDK 侧无需变更。
- 凭据泄露应急：轮换 secret（旧签名即刻失效）+ 清除锁定计数。

## 输入与输出防护

- 请求体大小限制（服务端默认 1MB，可配；端点 Servlet 硬上限 2MB）、字段校验、协议版本校验。
- SSE 字段正确编码，业务负载以 JSON 信封传输，不直接拼接进事件流。
- 日志：关联 messageId/clientId/traceId，不记录完整凭据与未脱敏负载。
- 错误响应不泄露凭据、内部堆栈、敏感数据。
- 内网明文 HTTP 的风险由接入方评估：HMAC 签名保证完整性与身份真实性，
  但不提供机密性（负载可被嗅探）。
