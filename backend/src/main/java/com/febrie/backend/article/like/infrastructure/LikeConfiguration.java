package com.febrie.backend.article.like.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 默认关闭业务入口，但SQL计数基线始终随文章建立。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LikeProperties.class)
public class LikeConfiguration { }
