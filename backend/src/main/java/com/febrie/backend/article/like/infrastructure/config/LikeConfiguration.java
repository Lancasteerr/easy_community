package com.febrie.backend.article.like.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 点赞属于固定业务能力，SQL计数基线始终随文章建立。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LikeProperties.class)
public class LikeConfiguration { }
