package com.febrie.backend.article.content.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 单独调度墓碑补写，Redis等待不会延迟索引投递和其他定时任务。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleVisibilityRepairConfiguration {
    @Bean
    ThreadPoolTaskScheduler articleVisibilityRepairScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("article-visibility-repair-");
        return scheduler;
    }
}
