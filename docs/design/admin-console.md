# 设计：linkora-admin-console 运维控制台

状态：**已实现**（0.4.0）。使用文档见 [docs/admin-console.md](../admin-console.md)；
UI 视觉稿见 [admin-console-mockup.html](admin-console-mockup.html)（与随 jar 发布的
`index.html` 同源）。实现落点：`linkora-admin-console` 模块（Servlet/Filter/Installer
+ 单页前端）与 `linkora-spring-boot-starter`（`LinkoraAdminConsoleAutoConfiguration`）。

## 1. 定位与形态

给服务端宿主提供的**嵌入式运维控制台**：一个可选 Maven 模块（jar），
引入后由同一内嵌容器直接服务页面与端点，**零新增部署物、零新增中间件、零前端构建**。

```
宿主应用（Spring Boot / MVC / 纯 Servlet）
  └── linkora-admin-console（可选 jar）
        ├── AdminConsoleServlet        页面与 REST 端点（同一 Servlet，路径前缀 /linkora-admin）
        ├── AdminConsoleFilter         Bearer Token 鉴权（默认关闭 = 完全不装配）
        └── /linkora-admin-console/index.html   单页控制台（静态资源，原生 HTML/CSS/JS）
```

- **默认关闭**：不引 jar / 不配置即完全不存在，对宿主零影响
- **开启**：`linkora.admin-console.enabled=true` + `token=...`（Boot starter 自动装配；
  MVC/纯 Servlet 宿主手动注册一个 Servlet）
- 访问：`http://服务端地址:端口/linkora-admin/`，输入 token 后存 sessionStorage
- 客户端运维不做独立界面：设备本地队列/死信通过既有指令通道（QUERY_OUTBOX /
  RETRY_OUTBOX_DEAD_LETTER 等）在控制台"客户端"页签远程操作

## 2. 安全

- 所有页面与端点经 `Authorization: Bearer <token>` 校验（恒定时间比较）；
  未携带/错误返回 401，页面端跳转 token 输入页
- token 独立于设备 HMAC 体系（设备凭据是机器身份；控制台是人的入口）
- **不提供** CORS（仅同源访问）；文档明确要求生产置于内网或反代之后
- 写操作（重放/跳过/启停/踢连接/状态变更）全部要求二次确认 + reason 留痕

## 3. REST 契约（控制台专用，JSON）

| 端点 | 方法 | 对应管理方法 |
|---|---|---|
| `/api/overview` | GET | getClientOverviews + getDailyStats |
| `/api/clients/{id}` | GET | listUnfinishedDeliveries |
| `/api/messages` | GET | listMessages（clientId/type/时间/分页） |
| `/api/messages/{id}` | GET | getMessageDetail |
| `/api/dead-letters` | GET | listDeadLetters |
| `/api/stats` | GET | getDailyStats |
| `/api/actions/retry-dead-letter` | POST | retryDeadLetter |
| `/api/actions/skip` | POST | skipMessage |
| `/api/actions/batch-skip` | POST | skipAllDeadLetters |
| `/api/actions/disconnect` | POST | disconnectClient |
| `/api/actions/enable` / `disable` | POST | enableClient / disableClient |
| `/api/actions/force-transition` | POST | forceTransition（target/reason） |
| `/api/actions/client-command` | POST | sendClientCommand（PING/QUERY_OUTBOX/重发/放弃） |
| `/api/command-result/{queryId}` | GET | getCommandResult |

错误响应：`{"error": "..."}` + 400/401/500；所有写操作接受 `reason` 字段并透传留痕。

## 4. UI 设计（重点）

### 4.1 视觉语言：暖白浅色、克制的质感

- **背景**：暖白 `#FAF9F6`（页面）/ 纯白 `#FFFFFF`（卡片），层次靠 1px 描边
  `#EBE8E3` 与极轻阴影（`0 1px 3px rgba(0,0,0,.04)`）
- **主色**：墨绿 `#2F5D50`（导航选中、主按钮、链接）——低饱和、和暖白同族的沉稳色
- **状态色**（低饱和、语义清晰）：
  - 成功/在线 `#3E7C4F` · 重试/警告 `#B4762A` · 死信/危险 `#A8442E` · 离线/中性 `#8A857C`
  - 状态一律以"色点 + 中文"胶囊形式呈现，不使用大红大绿
- **字体**：系统栈（`-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`），
  正文 14px / 辅助 12px / 数字用 tabular-nums；标题 500 字重不加粗到头
- **间距与圆角**：8px 网格；卡片圆角 10px、按钮 8px、胶囊全圆角
- **全部颜色走 CSS 变量**（`:root` 一处定义），无散落硬编码；后续换肤/暗色只改变量

### 4.2 布局

```
┌──────────┬────────────────────────────────────────────┐
│  侧栏     │  顶栏：页标题 · 自动刷新(10s,可暂停) · 退出   │
│  Logo    ├────────────────────────────────────────────┤
│  ▎总览    │                                            │
│  消息     │            内容区（卡片栅格）                 │
│  死信处置 │                                            │
│  客户端   │                                            │
│          │                                            │
│  底部:版本│                                            │
└──────────┴────────────────────────────────────────────┘
```

- 左侧固定导航 200px（收窄为图标 64px 可切换）；内容区最大 1280px 居中
- 首次进入：token 输入页（居中卡片，无侧栏）

### 4.3 页面结构

**总览**：4 张 KPI 卡（在线客户端 / 未完成投递 / 死信 / 今日发布）+ 客户端表
（在线状态胶囊、积压、死信数、注册时间，行点击进该客户端明细）+ 近 7 日发布量条形图（纯 CSS 柱）

**消息**：筛选栏（clientId / 类型 / 时间段）+ 分页表格（状态胶囊、顺序键、时间）；
点行右侧滑出**详情抽屉**（完整 payload 代码块 + 每个目标的投递时间线）

**死信处置**：死信表格（原因、payload 预览、停留时长高亮）→ 行操作"重放 / 跳过"，
勾选多行批量跳过；"通用状态变更"独立按钮弹模态（target 下拉 + reason 必填）
——**所有写操作：确认弹窗列明影响 + reason 输入框**

**客户端**：客户端卡片或表格 → 启停开关（带确认）、踢连接（带确认）、
未完成明细展开行；远程指令面板（PING / 查询设备 outbox / 远程重发死信），
结果以只读代码块呈现（指令经上行回传，页面轮询 queryId）

### 4.4 交互细节

- 自动刷新 10s（可暂停，写操作后强制刷新一次）；刷新不打断输入中的表单
- 加载：骨架屏（卡片/行占位）；空态：一句话引导（如"当前没有死信"）
- 错误：右上 toast（401 → 跳回 token 页）；写操作成功后 toast + 刷新
- 表格时间列人类可读；数字列 tabular-nums 对齐

## 5. 技术实现要点

- 单文件 `index.html`（内联 CSS/JS，约 800~1000 行），无构建、无框架、无外部 CDN
  ——离线内网可用；Servlet 以 `text/html` 直出静态资源
- 数据层单一 `api.js` 封装（fetch + token 注入 + 错误归一），页面逻辑与渲染分离
- Java 侧仅两类类：`AdminConsoleServlet`（页面 + REST 路由，调用 LinkoraServer 管理方法）
  与 `AdminConsoleFilter`（token 校验）；不引入新依赖（javax.servlet + Jackson 随核心已有）
- Boot starter 提供 `linkora.admin-console.*` 自动装配；MVC/纯 Servlet 提供手动注册说明

## 6. 验收标准

1. 真实 Boot 宿主挂载 → 浏览器流程全通（HTTP 断言：token 401/放行、各端点 JSON、
   写操作生效且 reason 留痕）
2. 默认不启用时宿主行为与现在逐字节一致（端点 404）
3. 193 个既有测试回归全绿；新增端点鉴权测试（无 token/错 token → 401）
4. UI 走查：四页签空态/加载/错误态齐全；写操作全部二次确认 + reason
