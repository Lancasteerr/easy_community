package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** 仅在已确认提交后调用；失败留给固定TTL/维护窗口，不回放SQL或旧正文。 */
@Component
public class ArticleCacheMaintenance {
    private static final Logger log = LoggerFactory.getLogger(ArticleCacheMaintenance.class);
    private final ArticleCache cache;
    public ArticleCacheMaintenance(ArticleCache cache) { this.cache = cache; }
    public void afterCommit(long id, boolean hidden) {
        long deadline = System.nanoTime() + ArticleCache.WAIT_BUDGET.toNanos();
        for (int attempt = 0; attempt < 3; attempt++) {
            String token = null;
            try {
                token = cache.tryLock(id);
                if (token != null && (hidden ? cache.hide(id, token) : cache.invalidate(id, token))) { return; }
            } catch (RuntimeException error) {
                log.warn("文章提交后缓存维护异常 articleId={} hide={} attempt={} error={}", id, hidden, attempt + 1, error.getClass().getSimpleName());
            } finally { if (token != null) { cache.release(id, token); } }
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) { break; }
            LockSupport.parkNanos(Math.min(25_000_000, Math.max(0, deadline - System.nanoTime())));
        }
        cache.record("invalidation_failed");
        log.warn("文章提交后双缓存维护未完成 articleId={} hide={}", id, hidden);
    }
}
