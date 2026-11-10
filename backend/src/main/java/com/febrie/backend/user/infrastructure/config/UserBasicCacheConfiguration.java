package com.febrie.backend.user.infrastructure.config;

import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 在应用启动时校验环境隔离，避免跨环境读写资料。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserBasicCacheProperties.class)
public class UserBasicCacheConfiguration {
    public UserBasicCacheConfiguration(UserBasicCacheProperties cache, AuthProperties auth) {
        if (!cache.namespace().startsWith(auth.redisPrefix() + ":user-basic:")) {
            throw new IllegalArgumentException("用户缓存必须位于当前环境的Redis前缀下");
        }
    }
}
