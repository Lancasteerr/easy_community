package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.ArticleDetailView;
import com.febrie.backend.article.content.application.dto.ArticleDetailData;
import com.febrie.backend.article.content.application.dto.ArticleEditView;
import com.febrie.backend.article.content.application.dto.ArticlePage;
import com.febrie.backend.article.content.application.dto.LoadedArticle;
import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.content.infrastructure.cache.ArticleDetailCacheStore;
import com.febrie.backend.shared.cache.CacheValue;

import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.shared.error.ApiException;
import java.util.*;
import com.febrie.backend.shared.cache.CacheProtocol;
import java.util.concurrent.Semaphore;
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
    private final ArticleDisplayAssembler display;
    private final Semaphore sourceReads = new Semaphore(4);
    public ArticleQueryService(ArticleReader reader, ArticleCache cache, ArticleDetailCacheStore details, ArticleListHydrator lists, ArticleDisplayAssembler display) {
        this.reader = reader; this.cache = cache; this.details = details; this.lists = lists; this.display = display;
    }
    public ArticleDetailView detail(long id) {
        try { return display.detail(cachedDetail(id)); }
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
        try { return new ArticlePage(display.lists(lists.authorPage(actor.userId(), selected.ids())),  page, size, selected.total()); }
        catch (ApiException error) { throw error; }
        catch (RuntimeException error) {
            log.warn("文章列表依赖异常 error={}", error.getClass().getSimpleName());
            throw ArticleErrors.unavailable();
        }
    }

    private ArticleDetailData cachedDetail(long id) {
        long deadline = System.nanoTime() + ArticleCache.WAIT_BUDGET.toNanos();
        while (true) {
            if (cache.hidden(id)) { throw ArticleErrors.notFound(); }
            var value = details.get(id);
            log.debug("文章详情缓存 articleId={} state={}", id, value.state());
            if (value.state() == CacheValue.State.NEGATIVE) { throw ArticleErrors.notFound(); }
            if (value.state() == CacheValue.State.HIT) { return visible(id, value.data()); }
            if (!sourceReads.tryAcquire()) { throw ArticleErrors.unavailable(); }
            String token = null;
            CacheValue<ArticleDetailData> checked = CacheValue.miss();
            try {
                token = cache.tryLock(id);
                if (token != null) { checked = loadDetailLocked(id, token); }
            } finally {
                if (token != null) { cache.release(id, token); }
                sourceReads.release();
            }
            // 可见性检查和响应组装均在释放文章锁之后执行。
            if (checked.state() == CacheValue.State.NEGATIVE) { throw ArticleErrors.notFound(); }
            if (checked.state() == CacheValue.State.HIT) { return visible(id, checked.data()); }
            if (!CacheProtocol.awaitRetry(deadline)) { throw ArticleErrors.unavailable(); }
        }
    }

    /** 已持有当前文章锁；只执行二次检查、短事务回源与回填。 */
    private CacheValue<ArticleDetailData> loadDetailLocked(long id, String token) {
        if (cache.hidden(id)) { return CacheValue.negative(); }
        var value = details.get(id);
        if (value.state() != CacheValue.State.MISS) { return value; }
        var loaded = reader.detail(id);
        boolean published = switch (loaded.state()) {
            case PUBLIC -> details.publish(id, token, loaded.data());
            case MISSING -> cache.missing(id, token);
            case HIDDEN -> cache.hide(id, token);
        };
        if (!published) { return CacheValue.miss(); }
        return loaded.state() == LoadedArticle.State.PUBLIC ? CacheValue.hit(loaded.data()) : CacheValue.negative();
    }
    private ArticleDetailData visible(long id, ArticleDetailData data) {
        if (cache.hidden(id)) { throw ArticleErrors.notFound(); }
        return data;
    }
}
