# mvc-client：Spring MVC 客户端示例（Tomcat）

独立 pom（引用 Maven Central 正式包），`LinkoraClientConfiguration` 手动装配。

## 启动

```bash
mvn package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/classes:$(cat target/cp.txt)" \
     com.linkora.example.mvcclient.ClientDemoApplication
```

- 对端配置见 `src/main/resources/linkora-client.properties`
  （`-Dlinkora.client.*` 可覆盖）
- 存储模式：`-Dlinkora.client.store=datasource` 切换宿主库同事务
  （需宿主 DataSource Bean；默认 sqlite 本地文件）
