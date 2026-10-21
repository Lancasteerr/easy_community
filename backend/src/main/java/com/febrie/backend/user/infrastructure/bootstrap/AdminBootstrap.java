package com.febrie.backend.user.infrastructure.bootstrap;

import com.febrie.backend.user.application.UserService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

/** 受控的一次性非Web初始化，没有默认凭证，也没有公网初始化接口。 */
@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final UserService users;
    private final Environment environment;
    private final ApplicationContext context;

    public AdminBootstrap(UserService users, Environment environment, ApplicationContext context) {
        this.users = users;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (context instanceof WebApplicationContext) {
            throw new IllegalStateException("管理员初始化必须通过非Web模式执行");
        }
        users.bootstrapAdmin(environment.getRequiredProperty("ADMIN_USERNAME"), environment.getRequiredProperty("ADMIN_PASSWORD"));
    }
}
