package com.febrie.backend.article.content.infrastructure.scheduling;

import com.febrie.backend.article.content.application.ArticleCacheMaintenance;
import com.febrie.backend.article.content.application.ArticleVisibilityRepairs;
import com.febrie.backend.shared.cache.CacheMetrics;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每轮只处理已提交领取的待办，单篇失败留待下轮重试。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleVisibilityRepairTask {
    private static final Logger log = LoggerFactory.getLogger(ArticleVisibilityRepairTask.class);
    private final ArticleVisibilityRepairs repairs;
    private final ArticleCacheMaintenance cache;
    private final CacheMetrics metrics;

    public ArticleVisibilityRepairTask(ArticleVisibilityRepairs repairs, ArticleCacheMaintenance cache, CacheMetrics metrics) {
        this.repairs = repairs;
        this.cache = cache;
        this.metrics = metrics;
    }

    @Scheduled(scheduler = "articleVisibilityRepairScheduler", fixedDelay = 1_000, initialDelay = 1_000)
    public void repair() {
        List<Long> ids;
        try {
            ids = repairs.claim();
        } catch (RuntimeException failure) {
            metrics.record("article", "visibility_repair_claim_failed");
            log.warn("领取文章墓碑待办失败 error={}", failure.getClass().getSimpleName());
            return;
        }
        for (long id : ids) {
            try {
                boolean repaired = cache.repairHidden(id);
                metrics.record("article", repaired ? "visibility_repair_completed" : "visibility_repair_busy");
            } catch (RuntimeException failure) {
                metrics.record("article", "visibility_repair_failed");
                log.warn("补写文章墓碑失败 articleId={} error={}", id, failure.getClass().getSimpleName());
            }
        }
    }
}
