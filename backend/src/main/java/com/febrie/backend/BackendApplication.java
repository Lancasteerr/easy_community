package com.febrie.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(BackendApplication.class, args);
        // 初始化命令执行成功后关闭连接池并退出，普通Web启动不受影响。
        if (context.getEnvironment().getProperty("app.bootstrap.enabled", Boolean.class, false)) {
            System.exit(SpringApplication.exit(context));
        }
    }

}
