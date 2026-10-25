# 运维控制台（linkora-admin-console）使用文档

嵌入式运维控制台：一个可选 Maven 模块（jar），引入后由宿主**同一个 Servlet 容器**
直接服务页面与端点。零新增部署物、零新增中间件、零前端构建、完全离线可用
（前端资源与 Vue 3 / Chart.js 库全部内嵌在 jar 内，无任何 CDN / 在线字体 / 在线图标请求）。

> 适用版本：0.4.1 及以上。控制台随 SDK 版本一起发布，前端资源与后端端点
> 必须来自**同一个版本**的 jar（混装新旧版本会出现"界面新、数据旧"的错乱）。

---

## 1. 定位与边界

- 控制台是**人的运维入口**：概览健康度、消息追踪、死信处置、客户端管理、远程指令
- 访问凭证是独立的 Bearer Token，与设备 HMAC 体系互不相干
  （设备凭据是机器身份，Token 是控制台身份）
- 客户端侧运维不提供独立界面：设备本地队列/死信通过"客户端详情 → 发件箱"与
  "远程操作"页的指令通道（PING / QUERY_OUTBOX / 重发 / 放弃）操作
- **默认关闭**：不引入 jar / 不配置即完全不装配，对宿主零影响

## 2. 前置条件

无论哪种宿主，控制台都挂在一个**已构建好的 `LinkoraServer` 实例**上：

- Spring Boot 宿主：由 `linkora-spring-boot-starter` 自动装配出 `LinkoraServer` Bean
- MVC / 纯 Servlet 宿主：你自己 `LinkoraServer.builder()...build()` 出实例

控制台**不创建、不管理** `LinkoraServer`，只读取它的管理方法——所以不存在
"控制台和业务服务端口冲突"的问题：它就跑在宿主自己的端口上。

---

## 3. Spring Boot 宿主接入（自动装配）

### 3.1 引入依赖

```xml
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-spring-boot-starter</artifactId>
    <version>0.4.1</version>
</dependency>
<!-- 控制台（starter 中为 optional 传递，需显式声明） -->
<dependency>
    <groupId>io.github.lianghang1</groupId>
    <artifactId>linkora-admin-console</artifactId>
    <version>0.4.1</version>
</dependency>
```

### 3.2 配置

```yaml
linkora:
  server:
    # ... 你已有的服务端配置（数据库、认证等）
  admin-console:
    enabled: true            # 默认 false：不配置即完全不装配，零影响
    token: "你的访问令牌"      # 启用时必填，缺失则应用启动直接失败
    path: /linkora-admin     # 挂载路径前缀（可选，默认 /linkora-admin）
```

### 3.3 装配行为（自动装配做了什么）

| 条件 | 行为 |
|---|---|
| 未引 jar / 未配置 `enabled=true` | 完全不装配，端点 404，宿主行为与不引入时一致 |
| `enabled=true` 但未配置 `token` | **应用启动失败**（快速失败），杜绝无鉴权控制台暴露在公网 |
| 正常开启 | 注册 `AdminConsoleServlet`（页面 + REST）与 `AdminConsoleFilter`（Token 校验），路径 `path/*`，Filter order 0 |

装配前提：容器中存在 `LinkoraServer` Bean（即你已正常配置了服务端 starter）。

### 3.4 访问

启动后浏览器打开 `http://服务端地址:端口/linkora-admin/`，输入 Token 进入。

---

## 4. Spring MVC / 纯 Servlet 宿主接入（手动装配）

没有 starter 的宿主，用 `AdminConsoleInstaller` 手动注册。它提供两个重载：

```java
// 以默认路径 /linkora-admin 装配
AdminConsoleInstaller.register(ServletContext ctx, LinkoraServer server, String token);

// 自定义路径（不能为空或 "/"）
AdminConsoleInstaller.register(ServletContext ctx, LinkoraServer server,
                               String token, String path);
```

token 为空或 path 非法时抛 `IllegalArgumentException`——**不给令牌宁可装配失败**，
与 Boot 侧的快速失败语义一致。

### 4.1 war 包部署（标准 Servlet 宿主）

在 `ServletContextListener#contextInitialized` 中装配（此时规范 Servlet API
允许动态注册）：

```java
public class AdminConsoleInit implements ServletContextListener {
    private LinkoraServer server;   // 你已有的服务端实例

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        AdminConsoleInstaller.register(
                sce.getServletContext(), server, System.getenv("CONSOLE_TOKEN"));
        // 控制台位于 /linkora-admin/*
    }
}
```

web.xml 无需任何配置。

### 4.2 内嵌 Tomcat（Spring MVC 以编程方式启动）

**注意一个已知的坑**：Spring 容器刷新完成后，嵌入式 Tomcat 会拒绝通过标准
Servlet API 动态注册 Servlet/Filter（`IllegalStateException`）。此时必须用
**Tomcat 原生 API** 注册（本项目 MVC 示例即此做法，可直接参考
`examples/spring-mvc-server/server-app` 的 `SpringMvcServerMain`）：

```java
Tomcat tomcat = new Tomcat();
Context tomcatCtx = tomcat.addContext("", baseDir);

// ... 先注册 linkora 的 /events /messages /acks 三个端点 ...

// 控制台：Tomcat 原生 API 注册（与三端点同方式）
Tomcat.addServlet(tomcatCtx, "linkoraAdminConsoleServlet",
        new AdminConsoleServlet(server));
tomcatCtx.addServletMappingDecoded("/linkora-admin/*", "linkoraAdminConsoleServlet");
// AdminConsoleFilter 同样用原生 API 注册（new AdminConsoleFilter(token)）
```

> 判断用哪种方式：**war 包 / 宿主初始化期（contextInitialized）→ Installer；
> 内嵌 Tomcat 编程式启动、Spring 刷新之后 → Tomcat 原生 API**。

### 4.3 MVC 完整示例

参考仓库 `examples/spring-mvc-server/server-app`：

- 启动命令：`java -Dlinkora.admin.console.token=你的令牌 -cp ... SpringMvcServerMain 1001`
- 数据源默认连配置的 MySQL（示例连 `115.159.41.195:3306/linkora`，可用
  `-Dlinkora.datasource.url/user/password` 覆盖）
- 该示例同时演示了三个业务端点 + 控制台 + 消息触发测试页（`/test-page`）的完整形态

---

## 5. 访问与安全

- 浏览器打开 `http://服务端地址:端口/linkora-admin/`，输入 Token 进入
  （Token 存 sessionStorage，关闭标签页即失效，无"记住我"）
- 所有 API 经 `Authorization: Bearer <token>` 校验（**恒定时间比较**），
  未携带/错误一律 401；页面本身放行（浏览器首跳无法带自定义头）
- 不提供 CORS（仅同源访问）；生产环境务必置于内网或反向代理之后
- **所有写操作**（重试/跳过/启停/断开/注销/注册/状态变更/下发指令）均要求
  填写原因，原因写入服务端日志留痕
- viewport 已禁用缩放；移动端自动适配（详情整页覆盖 + 返回）

---

## 6. 页面说明（五页）

| 页面 | 内容 |
|---|---|
| **概览** | 四指标卡（客户端总数/未完成投递/死信/在线率）+ 近 7 日发布趋势 + 客户端状态环形图 + 最近消息/客户端摘要；死信卡与未完成卡可点击直达对应页面 |
| **消息** | 三分类（全部/未完成投递/死信入口）+ 筛选工具栏（客户端下拉/精确 ID/时间范围/清除筛选/刷新，全部真实生效）+ 左右布局（左列表右详情）；详情三区：基本信息（含同键阻塞提示）/ Payload（可复制）/ 投递记录时间线 |
| **死信** | 独立处置页：客户端筛选 + 失败原因列 + 行内重试/跳过 + 批量跳过 + 通用状态变更 + 右侧详情 |
| **客户端** | 左窄导航列（ID + 状态点 + 死信徽标）+ 右固定高详情：信息、启停开关、PING、操作菜单（断开/注销）、发件箱快照拉取（六态状态机）与上行死信重发/放弃 |
| **远程操作** | 表单下发指令（PING / QUERY_OUTBOX）+ 执行记录列表（已提交/等待回执/已完成/失败/超时/暂未取得结果六态，queryId 语义显式） |

桌面端固定视口（浏览器不滚，滚动在列表/详情内部）；移动端（<1100px）详情转
整页覆盖 + 返回按钮，导航横向滑动。

---

## 7. REST 端点全表

全部位于 `<path>/api/**`，JSON。查询类 GET，操作类 POST（body 含 `reason` 必填）。

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/overview` | GET | 客户端概览 + 近 7 日统计 |
| `/api/clients` | GET | 全部客户端概览（状态/连接/未完成/死信计数） |
| `/api/clients/{id}/unfinished` | GET | 指定客户端未完成投递 |
| `/api/messages` | GET | 消息列表（clientId/type/begin/end/page/size），返回真实聚合投递状态 |
| `/api/messages/{id}` | GET | 消息详情（完整 payload + 投递记录） |
| `/api/dead-letters?clientId=` | GET | 指定客户端死信列表 |
| `/api/stats?days=7` | GET | 每日发布量 + 投递状态分布 |
| `/api/seq-blocked?clientId=&messageId=` | GET | 同键阻塞统计（该顺序键下除自身外仍处非终态的条数） |
| `/api/actions/retry-dead-letter` | POST | 重试死信（clientId/messageId/reason） |
| `/api/actions/skip` | POST | 跳过消息 → EXPIRED（不撤回已在途推送） |
| `/api/actions/batch-skip` | POST | 批量跳过指定客户端死信 |
| `/api/actions/batch-retry` | POST | 批量重试指定客户端死信 |
| `/api/actions/enable` / `disable` | POST | 启用/禁用客户端 |
| `/api/actions/register-client` | POST | 注册新客户端（`newClientId`/`enabled`/`reason`） |
| `/api/actions/remove-client` | POST | 注销客户端（移出白名单；历史数据保留） |
| `/api/actions/disconnect` | POST | 断开 SSE 连接 |
| `/api/actions/force-transition` | POST | 人工状态变更（仅合法转移，reason 必填） |
| `/api/actions/client-command` | POST | 下发远程指令（PING / QUERY_OUTBOX / RETRY_OUTBOX_DEAD_LETTER / DISCARD_OUTBOX_DEAD_LETTER） |
| `/api/command-result/{queryId}` | GET | 读取远程查询回执 |

错误统一返回 `{"error": "..."}` + 400/401/404/500。

---

## 8. 远程指令通道说明

指令经 SSE `ctrl` 事件实时下发，与业务消息完全隔离：**不落库、不重试、
不进客户端业务队列**。

| 指令 | 类型 | 语义 |
|---|---|---|
| `PING` | 同步 | 送达即探测完成（返回送达连接数），无异步回执 |
| `QUERY_OUTBOX` | 异步 | 拉取设备本地 SQLite 队列快照（可按状态过滤，上限 50 条）；结果由客户端经上行回传，按 queryId 轮询读取 |
| `RETRY_OUTBOX_DEAD_LETTER` | 即发即忘 | 远程触发客户端重发指定上行死信 |
| `DISCARD_OUTBOX_DEAD_LETTER` | 即发即忘 | 远程丢弃指定上行死信（需 messageId + reason） |

**关键语义**：提交成功 ≠ 执行完成。只有结果接口明确返回结果才算完成；
客户端离线时指令直接失败；控制台对异步指令轮询 30 秒（2s × 15 次）后标记
"暂未取得结果"，可手动重查。

---

## 9. 离线资源与升级说明

- 前端为 Vue 3（Global Build）+ Chart.js + 自有 CSS，全部内嵌在 jar 的
  `linkora-admin-console/` 资源目录（含 `vendor/`），内网无外网环境可用
- 升级时请**同时替换** `linkora-admin-console-*.jar`（含前端）与
  `linkora-server-*.jar`（含端点实现），保持同版本；只换其一会出现
  前后端不匹配（表现为状态字段缺失/分页异常）
- 升级后浏览器需强制刷新（Ctrl+F5），旧页面缓存可能与新端点不匹配

## 10. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 页面 404 | Token 未配置 → 控制台按安全默认完全不装配；确认 `enabled=true` 且 Token 已设置并重启 |
| 页面反复要求输入 Token | Token 错误或服务端已更换配置，重新输入 |
| 写操作 400 | 状态机拒绝（如非法转移），错误信息含当前/目标状态 |
| 状态字段显示不全 / 分页异常 | 前后端 jar 版本不一致（见 §9），对齐两个 jar 后强刷 |
| 指令送达 0 / 下发失败 | 客户端离线，指令通道不重试，客户端上线后重新下发 |
| QUERY_OUTBOX 显示"暂未取得结果" | 客户端可能未执行或中途离线；点"重新查询"或再次拉取 |
| 内嵌 Tomcat 注册失败 | Spring 刷新后不能用标准 Servlet API 动态注册，改用 Tomcat 原生 API（见 §4.2） |
