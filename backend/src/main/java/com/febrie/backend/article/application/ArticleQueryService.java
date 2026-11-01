package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.*;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.article.infrastructure.cache.*;
import com.febrie.backend.shared.error.ApiException;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 查询入口不建立长事务，主库短视图由独立Reader负责。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleQueryService {
    private static final Logger log = LoggerFactory.getLogger(ArticleQueryService.class);
    private final ArticleReader reader;
    private final ArticleCache cache;
    private final ArticleDetailCacheStore details;
    private final ArticleListHydrator lists;
    private final ArticleViewService views;
    public ArticleQueryService(ArticleReader reader, ArticleCache cache, ArticleDetailCacheStore details,
                               ArticleListHydrator lists, ArticleViewService views) {
        this.reader = reader; this.cache = cache; this.details = details; this.lists = lists; this.views = views;
    }
    public ArticleDetailView detail(long id) {
        try {
            var content = cachedDetail(id);
            // 计数位于整个缓存重试流程之后，成功取得可见详情才执行一次。
            return content.withViewCount(views.record(id));
        }
        catch (ApiException error) { throw error; }
        catch (RuntimeException error) {
            log.warn("文章详情依赖异常 articleId={} error={}", id, error.getClass().getSimpleName());
            throw ArticleErrors.unavailable();
        }
    }
    public ArticleEditView edit(UserActor actor, long id) { return reader.edit(actor, id); }
    public ArticlePage page(UserActor actor, int page, int size) {
        if (page < 1 || size < 1 || size > 50) { throw ArticleErrors.invalid(); }
        var selected = reader.page(actor.userId(), page, size);
        try {
            var content = lists.authorPage(actor.userId(), selected.ids());
            var totals = views.totals(content.stream().map(item -> Long.parseLong(item.articleId())).toList());
            var items = content.stream().map(item -> item.withViewCount(
                    Objects.requireNonNull(totals.get(Long.parseLong(item.articleId())), "文章总量未装配"))).toList();
            return new ArticlePage(items, page, size, selected.total());
        }
        catch (ApiException error) { throw error; }
        catch (RuntimeException error) {
            log.warn("文章列表依赖异常 error={}", error.getClass().getSimpleName());
            throw ArticleErrors.unavailable();
        }
    }

    private ArticleDetailView cachedDetail(long id) {
        long deadline = System.nanoTime() + ArticleCache.WAIT_BUDGET.toNanos();
        while (true) {
            if (cache.hidden(id)) { throw ArticleErrors.notFound(); }
            var value = details.get(id);
            log.debug("文章详情缓存 articleId={} state={}", id, value.state());
            if (value.state() == CacheValue.State.NEGATIVE) { throw ArticleErrors.notFound(); }
            if (value.state() == CacheValue.State.HIT) { return visible(id, value.data()); }
            String token = cache.tryLock(id);
            if (token != null) {
                try {
                    if (cache.hidden(id)) { throw ArticleErrors.notFound(); }
                    value = details.get(id);
                    if (value.state() == CacheValue.State.NEGATIVE) { throw ArticleErrors.notFound(); }
                    if (value.state() == CacheValue.State.HIT) { return visible(id, value.data()); }
                    var loaded = reader.detail(id);
                    boolean published = switch (loaded.state()) {
                        case PUBLIC -> details.publish(id, token, loaded.data());
                        case MISSING -> cache.missing(id, token);
                        case HIDDEN -> cache.hide(id, token);
                    };
                    if (!published) { log.debug("文章详情回填被拒 articleId={}", id); }
                    if (loaded.state() != LoadedArticle.State.PUBLIC) { throw ArticleErrors.notFound(); }
                    return visible(id, loaded.data());
                } finally { cache.release(id, token); }
            }
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) { throw ArticleErrors.unavailable(); }
            LockSupport.parkNanos(Math.min(25_000_000, Math.max(0, deadline - System.nanoTime())));
        }
    }
    private ArticleDetailView visible(long id, ArticleDetailView data) {
        if (cache.hidden(id)) { throw ArticleErrors.notFound(); }
        return data;
    }
}
