# boot-server：Spring Boot 服务端示例（内嵌 Tomcat + 运维控制台）

`linkora-spring-boot-starter` 自动装配 LinkoraServer 与 admin-console；
业务侧演示下行发布与**同库同事务**（transactional outbox）。

## 启动

```bash
# sqlite 模式（默认）：内嵌 ./linkora/linkora-server.db，零配置
mvn spring-boot:run
# datasource 模式：宿主 H2 内存库演示，生产替换 spring.datasource 为业务库
mvn spring-boot:run -Dspring-boot.run.profiles=datasource
```

- 控制台：http://127.0.0.1:8082/linkora-admin/（演示放行认证，allow-anonymous=true）
- 测试页：http://127.0.0.1:8082/test-page

## 同库同事务演示

```bash
# 提交：业务行与消息一起落库
curl -X POST "http://127.0.0.1:8082/api/demo/atomic-order?clientId=demo-client&orderNo=A-1&item=x"
# 回滚：publish 之后抛异常 → 业务行与消息一起消失
curl -X POST "http://127.0.0.1:8082/api/demo/atomic-order?clientId=demo-client&orderNo=A-2&item=x&fail=true"
curl "http://127.0.0.1:8082/api/demo/order-exists?orderNo=A-2"    # {"exists":false}
```

存储模式切换见 `application.yml` / `application-datasource.yml` 注释；
事务管理器显式绑定存储数据源的原因见 `TransactionConfig`。
