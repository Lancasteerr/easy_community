package com.febrie.backend.article.content.infrastructure.config;

import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 文章版本只影响自身前缀，不能误清认证或其他环境的Redis数据。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArticleCacheProperties.class)
public class ArticleCacheConfiguration {
    public ArticleCacheConfiguration(ArticleCacheProperties article, AuthProperties auth) {
        if (!article.namespace().startsWith(auth.redisPrefix() + ":article:")) {
            throw new IllegalArgumentException("文章缓存必须位于当前环境的Redis前缀下");
        }
    }
}
