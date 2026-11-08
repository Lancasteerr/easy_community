package com.febrie.backend.auth.infrastructure.config;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 校验最终运行环境与生产代理边界，防止混合环境或不完整的生产配置。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class RuntimeConfiguration {
    public RuntimeConfiguration(Environment env, AuthProperties properties) {
        long selected = Arrays.stream(env.getActiveProfiles()).filter(Set.of("dev", "prod", "test")::contains).count();
        if (selected != 1 || !Arrays.asList(env.getActiveProfiles()).contains(properties.environment())) {
            throw new IllegalStateException("必须且只能启用dev、prod或test中的一个运行环境");
        }
        String basePrefix = "ec:" + properties.environment();
        if (!(properties.redisPrefix().equals(basePrefix) || properties.redisPrefix().startsWith(basePrefix + ":"))
                || properties.sessionTtl().isNegative() || properties.sessionTtl().isZero()) {
            throw new IllegalStateException("Redis命名空间与环境不一致，或Session有效期无效");
        }
        if ("prod".equals(properties.environment())) {
            String proxies = env.getRequiredProperty("server.tomcat.remoteip.internal-proxies");
            Pattern.compile(proxies);
            if (proxies.isBlank() || proxies.equals(".*") || proxies.equals(".+") || !properties.secureCookie()) {
                throw new IllegalStateException("生产环境必须设置明确的可信Nginx地址范围并启用Secure Cookie");
            }
        }
    }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    PasswordEncoder passwordEncoder() {
        // 哈希带算法前缀，后续升级编码器不需要修改已有账号模型。
        return new DelegatingPasswordEncoder("bcrypt", java.util.Map.of("bcrypt", new BCryptPasswordEncoder(12)));
    }
}
