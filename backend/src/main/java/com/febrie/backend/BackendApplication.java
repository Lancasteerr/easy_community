package com.febrie.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.context.WebApplicationContext;

@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(BackendApplication.class, args);
        // 只有非 Web 初始化命令需要退出，正常 Web 启动完成初始化后继续提供服务。
        boolean bootstrapCommand = !(context instanceof WebApplicationContext)
                && context.getEnvironment().getProperty("app.bootstrap.enabled", Boolean.class, false);
        if (bootstrapCommand
                || context.getEnvironment().getProperty("app.article.cache-rebuild.enabled", Boolean.class, false)
                || !context.getEnvironment().getProperty("app.like.maintenance", "none").equals("none")
                || !context.getEnvironment().getProperty("app.search.maintenance", "none").equals("none")) {
            System.exit(SpringApplication.exit(context));
        }
    }

}
