package com.febrie.backend.user.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 用户展示缓存使用独立版本，不能与认证或文章命名空间混用。 */
@ConfigurationProperties("app.user-basic.cache")
public record UserBasicCacheProperties(String namespace) {
    public UserBasicCacheProperties {
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_\\-]+:user-basic:[A-Za-z0-9_\\-]{1,32}")) {
            throw new IllegalArgumentException("用户缓存命名空间必须包含环境前缀与用户版本");
        }
    }
}
