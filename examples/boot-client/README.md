# boot-client：Spring Boot 客户端示例（内嵌 Tomcat）

`linkora.client.*` 自动装配 LinkoraClient 与 UplinkPublisher；
演示上行发布、离线缓冲补发与**同库同事务**（datasource 模式）。

## 启动（对端为 boot-server，默认 http://127.0.0.1:8082）

```bash
mvn spring-boot:run                                                    # sqlite 模式（默认）
mvn spring-boot:run -Dspring-boot.run.profiles=datasource              # datasource 模式
# 自定义对端：追加 --linkora.client.server-url=http://host:port
```

启动后内置四向闭环演示自动运行（2 上行 2 下行），客户端长驻。

## REST 端点

- `POST /api/ship?orderNo=&status=` 普通上行
- `POST /api/demo/atomic-ship?orderNo=&fail=true` 同事务回滚演示（仅 datasource 模式）：
  业务行与 outbox 写入一起回滚，server 侧 `/api/uplink/order-status-updates`
  可验证"回滚的消息从未到达"
- `GET /api/demo/shipment-exists?orderNo=` 查证落库

client-only 宿主已显式 `linkora.server.enabled=false`（0.5.0 起服务端默认内嵌 SQLite、
不再依赖 DataSource 存在性，必须显式关闭）。
