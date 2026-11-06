package com.febrie.backend.shared.persistence;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** 主库只扫描业务 Mapper；通知执行器自行注册，不能混入此扫描范围。 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = {
        "com.febrie.backend.user.infrastructure.persistence.mapper",
        "com.febrie.backend.media.infrastructure.persistence.mapper",
        "com.febrie.backend.article.content.infrastructure.persistence.mapper",
        "com.febrie.backend.article.view.infrastructure.persistence.mapper",
        "com.febrie.backend.article.tag.infrastructure.persistence.mapper",
        "com.febrie.backend.article.like.infrastructure.mapper"
}, sqlSessionTemplateRef = "sqlSessionTemplate")
public class PersistenceConfiguration { }
