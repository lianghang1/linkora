# 安全设计（Security）

> 0.6.0：认证扩展为三种模式（HMAC / Bearer Token / 匿名），新增传输级
> payload 加密（默认开启）。原则不变：纯应用层，不依赖 PKI/证书。

## 一、三种认证模式（`linkora.server.auth-mode` / `linkora.client.auth-mode`）

| 模式 | 适用 | 服务端 | 客户端 |
|---|---|---|---|
| **hmac**（默认） | 高安全：签名+防重放+防爆破 | `HmacClientAuthenticator`（默认，需凭证） | `HmacCredentialProvider` |
| **token** | 宿主已有账号密码登录体系 | `TokenClientAuthenticator` + 宿主 `TokenValidator` Bean | 静态 `auth-token` 或 `TokenProvider` Bean |
| **anonymous** | 仅开发/演示 | `AllowAllClientAuthenticator` | 无凭据 |

选型建议：**宿主已有登录系统 → token（零改造对接）；纯设备间高安全 → hmac；
本地开发 → anonymous（生产禁用）**。

## 二、token 模式：对接已有账号体系

### 服务端：实现 TokenValidator，回调宿主账号体系

```java
@Service
public class HostTokenValidator implements TokenValidator {
    @Override
    public boolean validate(String clientId, String token) {
        // 查会话表 / 调认证服务 /token/introspect / 校验 JWT——任选，都是宿主已有能力
        return sessionService.isValid(clientId, token);
    }
}
```

```yaml
linkora:
  server:
    auth-mode: token
```

- 三个入口（`/events` `/messages` `/acks`）共用同一校验；失败统一 401 `AUTH_FAILED`
- 宿主校验器抛异常按认证失败处理（不放大为 500）
- 失败锁定策略由宿主账号体系承担（登录接口本身的防爆破兜底）

### 客户端：token 从哪来

```yaml
linkora:
  client:
    auth-mode: token
    auth-token: "宿主登录体系下发的 token"    # 方式一：静态（过期需换配置）
```

```java
// 方式二（推荐）：动态 TokenProvider——每次出站请求自动取最新 token，
// 宿主在内部完成续期/刷新，SDK 与 at-least-once 重试语义完全无感
@Bean
public TokenProvider tokenProvider() {
    return () -> hostSessionService.currentToken(clientId);
}
```

- 客户端自动附加 `Authorization: Bearer <token>`（兼容 `X-Linkora-Token` 头）
- token 过期 → 服务端 401 → 消息随 at-least-once 自动重试，续期后补投成功，零丢失
- token **禁止放入 URL**（避免进入反代/访问日志）

## 三、hmac 模式（默认）

### 请求签名算法

```text
bodyHash   = SHA-256(body).hexLower          （body 为空时对空字节数组求哈希）
canonical  = clientId + "\n" + HTTP方法 + "\n" + timestampMillis + "\n" + nonce + "\n" + bodyHash
signature  = HMAC-SHA256(secretBytes, canonical).hexLower
```

- 签名不包含 URL 路径（规避容器映射差异）；跨端点重放由 timestamp + nonce 拦截
- 全部恒定时间比较；纯 JDK crypto，无新增依赖

| 头名 | 说明 |
|---|---|
| `X-Linkora-Access-Token` | 服务端发放的高熵令牌（256 bit） |
| `X-Linkora-Timestamp` | epoch 毫秒，±5 分钟窗口 |
| `X-Linkora-Nonce` | 128 bit 一次性随机数 |
| `X-Linkora-Signature` | HMAC-SHA256 签名（hex 小写） |

### 防爆破与防重放

1. 256 bit 高熵令牌使在线爆破不可行
2. 失败指数锁定：同 clientId 连续失败 5 次 → 2^n 指数锁定（封顶 15 分钟）
3. 防枚举：未知 clientId 与错误凭据统一返回 `AUTH_FAILED`
4. 防重放：时间戳窗口 + nonce 缓存（10 万条有界）
5. 凭据存储为可插拔 SPI（`CredentialStore`）；双凭据并存实现平滑轮换；
   泄露应急 = 轮换 secret + 清锁定计数

## 四、传输加密（0.6.0 默认开启，与认证无关）

### 是什么

payload 线路上自动 AES-256-GCM 加密：客户端加密 → 服务端解密后进业务逻辑；
信封带 `"enc": "A256GCM:k1"` 标记。**业务代码零改动**，落库为明文（只管传输）。

```yaml
linkora:
  payload-cipher-key: "Qr1J5BCqFYkIlxfELSx6xYoZZ5YxYEZyWHZrBOJT83M="   # 两端一致
  # payload-cipher: false   # 如确不需要（纯内网已自评估），显式关闭
```

### 密钥管理（两条铁律）

1. **带外分发**：密钥与 server-url 一样走部署配置/配置中心（Nacos 等），
   **绝不在明文线路上在线下发**——在线下发 key 等于把房卡寄出去
2. **与认证解耦**：独立配置项，匿名部署也能加密；换认证方式不受影响

### 格式与开销

- 线格式自描述：`Base64(IV[12] + 密文 + Tag[16])`，固定开销 +28 字节
- 每次加密随机 IV（相同明文两次加密结果不同）；篡改任一字节解密即失败
  （失败走 ACK FAILED 进重试/死信，不会解出错误数据跑业务）
- 密文超过 64KB 的上行报文先 gzip 再加密（加密前压缩）

### 轮换与泄露应急

- 信封 keyId 位已预留；本期轮换 = 生成新 key + 两端配置更新重启
  （低峰执行，窗口内消息由 at-least-once 自动补投）
- 泄露应急 = 换 key 重启，泄露窗口止于换钥时刻
- 未来"单客户端泄露不波及其他"：演进为 per-client key（协议位已留）

### 边界（如实说明）

- 保护**传输与信封**：落库为明文（业务库自身的加密属业务系统安全范畴）
- 元数据（messageId/类型/时间/keyId）明文可见——要连元数据都藏需 HTTPS，
  与本机制不冲突也不重叠

## 五、缺省即拒绝（fail-fast）

| 场景 | 行为 |
|---|---|
| `auth-mode=token` 但无 TokenValidator Bean | 服务端启动失败 |
| `payload-cipher=true`（默认）但无 key | 两端启动失败 |
| 控制台 `enabled=true` 但无 token | 启动失败 |
| 无认证器且未显式 `allow-anonymous` / `auth-mode=anonymous` | 告警放行（仅 hmac 回退语义），生产应显式配置 |

## 六、输入与输出防护

- 请求体上限（默认 1MB，可配）、协议版本校验、SSE 字段编码
- 日志记录 messageId/clientId/traceId，不记录完整凭据与未脱敏负载
- 错误响应不泄露凭据、内部堆栈、敏感数据
