package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** 文章事务提交后维护投影。隐藏失败由主库待办补写，修改失效失败仍由TTL兜底。 */
@Component
public class ArticleCacheMaintenance {
    private static final Logger log = LoggerFactory.getLogger(ArticleCacheMaintenance.class);
    private final ArticleCache cache;
    private final ArticleVisibilityRepairs visibilityRepairs;

    public ArticleCacheMaintenance(ArticleCache cache, ArticleVisibilityRepairs visibilityRepairs) {
        this.cache = cache;
        this.visibilityRepairs = visibilityRepairs;
    }

    public void afterCommit(long id, boolean hidden) {
        long deadline = System.nanoTime() + ArticleCache.WAIT_BUDGET.toNanos();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (updateCache(id, hidden)) {
                    if (hidden) { completeAfterCommit(id); }
                    return;
                }
            } catch (RuntimeException error) {
                log.warn("文章提交后缓存维护异常 articleId={} hide={} attempt={} error={}", id, hidden, attempt + 1, error.getClass().getSimpleName());
            }
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) { break; }
            LockSupport.parkNanos(Math.min(25_000_000, Math.max(0, deadline - System.nanoTime())));
        }
        cache.record("invalidation_failed");
        log.warn("文章提交后双缓存维护未完成 articleId={} hide={}", id, hidden);
    }

    /** 后台领取后只尝试一次。确认写入并释放缓存锁后，才清理待办。 */
    public boolean repairHidden(long id) {
        if (!updateCache(id, true)) { return false; }
        visibilityRepairs.complete(id);
        return true;
    }

    private void completeAfterCommit(long id) {
        try { visibilityRepairs.complete(id); }
        catch (RuntimeException failure) {
            cache.record("visibility_repair_cleanup_failed");
            log.warn("隐藏墓碑已写入，清理待办失败 articleId={} error={}", id, failure.getClass().getSimpleName());
        }
    }

    private boolean updateCache(long id, boolean hidden) {
        String token = cache.tryLock(id);
        if (token == null) { return false; }
        try {
            return hidden ? cache.hide(id, token) : cache.invalidate(id, token);
        } finally {
            cache.release(id, token);
        }
    }
}
