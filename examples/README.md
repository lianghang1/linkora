# Linkora 全套示例（0.5.0 存储模型）

四个示例应用，覆盖 Spring Boot 与 Spring MVC（均为 Tomcat）、
两种存储模式（sqlite 内嵌 / datasource 宿主库同事务）：

| 应用 | 角色 | 依赖版本 |
|---|---|---|
| `boot-server` | 服务端 + 运维控制台（内嵌 Tomcat） | 仓库源码 |
| `boot-client` | 客户端嵌入业务服务（内嵌 Tomcat） | 仓库源码 |
| `mvc-server`  | 服务端 + 运维控制台（Tomcat war/内嵌 Tomcat） | Maven Central 正式包 |
| `mvc-client`  | 客户端（Tomcat） | Maven Central 正式包 |

## 存储模式（两端对等）

| 模式 | 语义 | 适用 |
|---|---|---|
| `sqlite`（默认） | 内嵌 SQLite 文件，零配置起步；boot 系默认 `./data/*.db` | 开发 / 演示 / 小规模部署 |
| `datasource` | 复用宿主 DataSource，业务与消息**同库同事务**（transactional outbox） | 生产推荐 |

## 启动与验证（以 boot-server 为例）

```bash
# sqlite 模式（默认，无需数据库）
mvn spring-boot:run
# datasource 模式（演示用 H2 内存库；生产替换 spring.datasource 为业务库）
mvn spring-boot:run -Dspring-boot.run.profiles=datasource
```

事务演示（datasource 模式下语义完整）：

```bash
# 业务行与消息同事务提交
curl -X POST "http://127.0.0.1:8092/api/demo/atomic-order?clientId=demo-client&orderNo=A-1&item=x"
# fail=true：publish 之后整体回滚——业务行与消息一起消失
curl -X POST "http://127.0.0.1:8092/api/demo/atomic-order?clientId=demo-client&orderNo=A-2&item=x&fail=true"
curl "http://127.0.0.1:8092/api/demo/order-exists?orderNo=A-2"   # → {"exists":false}
```

boot-client 同理：`/api/ship`（普通上行）、`/api/demo/atomic-ship?fail=true`（同事务回滚，
server 侧 `/api/uplink/order-status-updates` 可验证"回滚的消息从未到达"）。

## 各示例专属说明

- **boot-server / boot-client**：`application.yml`（sqlite 默认）+ `application-datasource.yml`
  （datasource profile）；boot-client 为 client-only 宿主，已显式 `linkora.server.enabled=false`
- **mvc-server**：`-Dlinkora.store=sqlite|mysql` 切换（默认 sqlite）；
  控制台令牌 `-Dlinkora.admin.console.token=...`；启动
  `java -Dlinkora.store=sqlite -Dlinkora.admin.console.token=t -cp "target/classes:$(cat target/cp.txt)" com.linkora.example.springmvc.SpringMvcServerMain 8081`
- **mvc-client**：`linkora-client.properties` 配置；`-Dlinkora.client.store=datasource`
  切换（需宿主 DataSource Bean）
