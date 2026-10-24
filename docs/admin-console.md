# 运维控制台（linkora-admin-console）

嵌入式运维控制台：一个可选 Maven 模块（jar），引入后由宿主同一 Servlet 容器
直接服务页面与端点。零新增部署物、零新增中间件、零前端构建。

## 1. 定位与边界

- 控制台是**人的运维入口**：查看总览、处置死信、启停客户端、下发远程指令
- 访问凭证是独立的 Bearer Token，与设备 HMAC 体系互不相干
  （设备凭据是机器身份，Token 是控制台身份）
- 客户端侧运维不提供独立界面：设备本地队列/死信通过控制台"客户端"页签的
  远程指令通道（PING / QUERY_OUTBOX / 重发 / 放弃）操作

## 2. 开启方式

### Spring Boot（starter 自动装配）

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-admin-console</artifactId>
</dependency>
```

```yaml
linkora:
  admin-console:
    enabled: true          # 默认 false：不配置即完全不装配
    token: "你的访问令牌"   # 启用时必填，缺失则装配期快速失败
    path: /linkora-admin   # 挂载路径前缀（可选）
```

### Spring MVC / 纯 Servlet 宿主（手动装配）

```java
// ServletContextListener#contextInitialized 中：
AdminConsoleInstaller.register(servletContext, linkoraServer, "你的访问令牌");
// 控制台位于 /linkora-admin/
```

## 3. 访问与安全

- 浏览器打开 `http://服务端地址:端口/linkora-admin/`，输入 Token 后进入
  （Token 保存在 sessionStorage，关闭标签页即失效）
- 所有页面与端点经 `Authorization: Bearer <token>` 校验，恒定时间比较；
  未携带/错误返回 401
- 不提供 CORS（仅同源访问）；生产环境务必置于内网或反向代理之后
- **所有写操作**（重放/跳过/启停/断开/状态变更/下发指令）均要求填写原因，
  原因写入服务端日志留痕

## 4. 页面与端点

四个页签：

| 页签 | 内容 |
|---|---|
| 驾驶舱 | KPI + 待办工单流（死信/重试中，带顺序键与同键阻塞提示）+ 近 7 日发布量 + 最近动态 |
| 消息 | 统一列表：状态分段器（全部/待处理/死信）+ 顶栏客户端筛选 + 点行右侧查看详情；死信视图含批量操作 |
| 客户端 | 卡片列表，点进详情：该客户端指标、远程指令、复用统一消息列表、未完成明细 |

REST 端点（全部位于 `<path>/api/**`，JSON）：

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/overview` | GET | 客户端概览 + 近 7 日统计 |
| `/api/clients` | GET | 全部客户端概览 |
| `/api/clients/{id}/unfinished` | GET | 指定客户端未完成投递 |
| `/api/messages` | GET | 消息列表（clientId/type/begin/end/page/size） |
| `/api/messages/{id}` | GET | 消息详情（完整 payload + 投递记录） |
| `/api/dead-letters?clientId=` | GET | 指定客户端死信列表 |
| `/api/stats?days=7` | GET | 每日发布量 + 投递状态分布 |
| `/api/actions/retry-dead-letter` | POST | 重放死信（clientId/messageId/reason） |
| `/api/actions/skip` | POST | 跳过消息 → EXPIRED |
| `/api/actions/batch-skip` | POST | 批量跳过指定客户端死信 |
| `/api/actions/batch-retry` | POST | 批量重放指定客户端死信 |
| `/api/actions/enable` / `disable` | POST | 启用/禁用客户端 |
| `/api/actions/disconnect` | POST | 断开 SSE 连接 |
| `/api/actions/force-transition` | POST | 人工状态变更（仅合法转移，reason 必填） |
| `/api/actions/client-command` | POST | 下发远程指令（白名单见 ClientCommands） |
| `/api/command-result/{queryId}` | GET | 读取远程查询回执 |
| `/api/seq-blocked` | GET | 同键阻塞统计（clientId + messageId，返回该顺序键下除自身外仍处非终态的条数） |

错误统一返回 `{"error": "..."}` + 400/401/404/500。

## 5. 远程指令通道说明

指令经 SSE `ctrl` 事件实时下发，与业务消息完全隔离：**不落库、不重试、
不进客户端业务队列**，仅对在线客户端生效（离线返回 0，需再次触发）。
指令白名单：`PING`、`QUERY_OUTBOX`、`RETRY_OUTBOX_DEAD_LETTER`、
`DISCARD_OUTBOX_DEAD_LETTER`。QUERY_OUTBOX 的结果由客户端经上行回传，
控制台按 queryId 轮询展示。

## 6. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 页面反复要求输入 Token | Token 错误或服务端已更换配置，重新输入 |
| 写操作 400 | 状态机拒绝（如非法转移），错误信息含当前/目标状态 |
| 指令送达 0 | 客户端离线，指令通道不重试，客户端上线后重新下发 |
| QUERY_OUTBOX 无回执 | 客户端离线或未执行；控制台轮询 30 秒后提示超时 |
