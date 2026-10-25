# mvc-server：Spring MVC 服务端示例（Tomcat）

独立 pom（引用 Maven Central 正式包），手动装配四仓储 SPI + AdminConsoleInstaller，
演示 war/内嵌 Tomcat 形态与存储模式切换。

## 启动（内嵌 Tomcat 编程式）

```bash
mvn package dependency:build-classpath -Dmdep.outputFile=target/cp.txt
# sqlite 模式（默认）：内嵌 ./data/linkora-mvc-server.db
java -Dlinkora.store=sqlite -Dlinkora.admin.console.token=令牌 \
     -cp "target/classes:$(cat target/cp.txt)" \
     com.linkora.example.springmvc.SpringMvcServerMain 8081
# mysql 模式（datasource）：默认连远程 MySQL，可用 -Dlinkora.datasource.url 指向 H2
java -Dlinkora.store=mysql -Dlinkora.admin.console.token=令牌 \
     -cp "target/classes:$(cat target/cp.txt)" \
     com.linkora.example.springmvc.SpringMvcServerMain 8081
```

- 控制台：http://127.0.0.1:8081/linkora-admin/（令牌即上面注入值）
- 消息触发测试页：http://127.0.0.1:8081/test-page
- 演示数据源默认连远程 MySQL（生产形态）；SQLite 模式零配置
