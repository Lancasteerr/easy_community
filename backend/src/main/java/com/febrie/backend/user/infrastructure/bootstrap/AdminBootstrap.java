package com.febrie.backend.user.infrastructure.bootstrap;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserService;
import com.febrie.backend.user.infrastructure.persistence.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 按配置初始化首个管理员；正常启动与非 Web 命令共享逻辑，不覆盖已有账号。 */
@Component
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private static final int MAX_ATTEMPTS = 3;

    private final UserService users;
    private final AccountRepository accounts;
    private final Environment environment;

    public AdminBootstrap(UserService users, AccountRepository accounts, Environment environment) {
        this.users = users;
        this.accounts = accounts;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 已有管理员时不读取初始凭证，允许部署完成后移除这些配置。
        if (skipIfInitialized()) { return; }
        String username = environment.getRequiredProperty("ADMIN_USERNAME");
        String password = environment.getRequiredProperty("ADMIN_PASSWORD");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                // 经服务代理开启独立事务；校验、编码及两张表的写入沿用现有规则。
                users.bootstrapAdmin(username, password);
                log.info("管理员初始化成功");
                return;
            } catch (ApiException failure) {
                if (!"ADMIN_ALREADY_INITIALIZED".equals(failure.code())) { throw failure; }
                log.info("管理员已存在，跳过初始化");
                return;
            } catch (PessimisticLockingFailureException conflict) {
                // 服务代理已完成事务回滚，重新读取并发结果后才决定是否重试。
                if (skipIfInitialized()) { return; }
                if (attempt == MAX_ATTEMPTS) { throw conflict; }
            }
        }
    }

    /** 数据库角色是唯一判据，禁用或删除状态的管理员也不会被重新创建。 */
    private boolean skipIfInitialized() {
        if (!accounts.hasAdmin()) { return false; }
        log.info("管理员已存在，跳过初始化");
        return true;
    }
}
