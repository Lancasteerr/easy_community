package com.febrie.backend.article.search.infrastructure.scheduling;

import com.febrie.backend.article.search.application.ArticleRankService;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 部署只在指定实例启用；两个扫描任务共用单线程调度器，无分布式排名锁。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.search.rank-scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ArticleRankTask {
    private static final Logger log = LoggerFactory.getLogger(ArticleRankTask.class);
    private final ArticleRankService ranks;
    private final SearchProperties p;
    public ArticleRankTask(ArticleRankService ranks, SearchProperties p) { this.ranks = ranks; this.p = p; }
    @Scheduled(scheduler = "articleRankScheduler", fixedDelay = 300000, initialDelay = 300000)
    public void refresh() { run(false); }
    @Scheduled(scheduler = "articleRankScheduler", cron = "0 10 3 * * *", zone = "Asia/Shanghai")
    public void calibrate() { run(true); }
    private void run(boolean full) {
        try {
            var result = ranks.scan(full, p.writeAlias(), true);
            if (result.missing() + result.failed() > 0) { log.warn("排名扫描未完整成功 full={} missing={} failed={}", full, result.missing(), result.failed()); }
        } catch (RuntimeException failure) { log.warn("排名扫描失败 full={} error={}", full, failure.getClass().getSimpleName()); }
    }
}
