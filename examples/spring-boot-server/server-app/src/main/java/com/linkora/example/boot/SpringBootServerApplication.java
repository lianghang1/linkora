package com.linkora.example.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 宿主示例入口：本类与应用配置就是全部工作——
 * LinkoraServer、三端点 Servlet、schema 迁移、客户端注册全部由
 * linkora-spring-boot-starter 自动装配完成（见 application.yml）。
 *
 * <p>运行方式（仓库根目录，首次需先 {@code mvn install -DskipTests}）：</p>
 * <pre>
 *   mvn -pl examples/spring-boot-server/server-app spring-boot:run
 * </pre>
 *
 * @author lianghang
 */
@SpringBootApplication
public class SpringBootServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootServerApplication.class, args);
    }
}
