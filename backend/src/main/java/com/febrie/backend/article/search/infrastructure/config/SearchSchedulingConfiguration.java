package com.febrie.backend.article.search.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 显式保留旧任务的默认调度器；索引网络等待和排名扫描分别隔离。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SearchSchedulingConfiguration {
    @Bean(name = "taskScheduler") @Primary @ConditionalOnMissingBean(name = "taskScheduler")
    ThreadPoolTaskScheduler defaultScheduler() { return scheduler("scheduled-"); }
    @Bean(name = "articleIndexRelayScheduler")
    ThreadPoolTaskScheduler relayScheduler() { return scheduler("article-index-relay-"); }
    @Bean(name = "articleRankScheduler")
    ThreadPoolTaskScheduler rankScheduler() { return scheduler("article-rank-"); }
    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        return scheduler;
    }
}
