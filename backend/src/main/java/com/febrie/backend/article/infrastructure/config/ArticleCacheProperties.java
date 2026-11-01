package com.febrie.backend.article.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 命名空间版本用于受控恢复；TTL与锁预算固定为本期已经验收的边界。 */
@ConfigurationProperties("app.article.cache")
public record ArticleCacheProperties(String namespace) {
    public ArticleCacheProperties {
        if (namespace == null || !namespace.matches("[A-Za-z0-9:_\\-]+:article:[A-Za-z0-9_\\-]{1,32}")) {
            throw new IllegalArgumentException("文章缓存命名空间必须包含环境前缀与文章版本");
        }
    }
}
