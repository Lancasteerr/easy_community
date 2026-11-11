package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.ArticleBriefData;
import com.febrie.backend.article.content.application.dto.LoadedArticle;
import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.content.infrastructure.cache.ArticleListCacheStore;
import com.febrie.backend.shared.cache.CacheValue;

import java.util.*;
import com.febrie.backend.shared.cache.CacheProtocol;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 批量读取文章简略投影；作者与计数在解锁后由业务层组装。 */
@Component
@Transactional(propagation = Propagation.NEVER)
public class ArticleListHydrator {
    private static final Logger log = LoggerFactory.getLogger(ArticleListHydrator.class);
    private final ArticleCache cache;
    private final ArticleListCacheStore lists;
    private final ArticleReader reader;
    private final java.util.concurrent.Semaphore sourceReads = new java.util.concurrent.Semaphore(4);
    public ArticleListHydrator(ArticleCache cache, ArticleListCacheStore lists, ArticleReader reader) {
        this.cache = cache; this.lists = lists; this.reader = reader;
    }

    private Assembly assemble(List<Long> ids) {
        Assembly assembly = new Assembly(ids);
        long deadline = System.nanoTime() + ArticleCache.WAIT_BUDGET.toNanos();
        while (!assembly.pending.isEmpty()) {
            var hidden = cache.hidden(assembly.pending);
            var values = lists.get(assembly.pending);
            Set<Long> misses = new LinkedHashSet<>();
            for (long id : new ArrayList<>(assembly.pending)) {
                if (!acceptCached(id, hidden.get(id), values.get(id), assembly)) { misses.add(id); }
            }

            if (misses.isEmpty()) { break; }
            if (!sourceReads.tryAcquire()) { cache.record("source_rejected"); throw ArticleErrors.unavailable(); }
            Map<Long, String> held = new LinkedHashMap<>();
            try {
                // 所有锁仅非阻塞尝试，不能持有A锁阻塞等待B锁。
                for (long id : misses) {
                    String token = cache.tryLock(id);
                    if (token != null) { held.put(id, token); }
                }
                if (!held.isEmpty()) { loadLocked(held, assembly); }
            } finally {
                held.forEach(cache::release);
                sourceReads.release();
            }
            if (!assembly.pending.isEmpty() && !CacheProtocol.awaitRetry(deadline)) {
                cache.record("contention_timeout");
                throw ArticleErrors.unavailable();
            }
        }

        return assembly;
    }

    /** ES候选不带作者授权前提；只跳过已知无效项，不额外强查主库状态。 */
    public List<ArticleBriefData> publicCandidates(List<Long> ids) {
        if (ids.isEmpty()) { return List.of(); }
        var assembly = assemble(ids);
        cache.hidden(assembly.result.keySet()).forEach((id, hidden) -> {
            if (hidden) { assembly.result.remove(id); }
        });
        return ids.stream().map(assembly.result::get).filter(Objects::nonNull).toList();
    }

    private boolean acceptCached(long id, boolean hidden, CacheValue<ArticleBriefData> value, Assembly assembly) {
        if (!hidden && value.state() == CacheValue.State.MISS) { return false; }
        if (!hidden && value.state() == CacheValue.State.HIT) { assembly.result.put(id, value.data()); }
        // 墓碑与负缓存直接过滤，不再依赖SQL选页结果重新核对。
        assembly.pending.remove(id);
        return true;
    }

    /** 进入时已持有这些文章的锁；二次检查后只合并仍缺失的项回源。 */
    private void loadLocked(Map<Long, String> held, Assembly assembly) {
        var hidden = cache.hidden(held.keySet());
        var values = lists.get(held.keySet());
        for (long id : new ArrayList<>(held.keySet())) {
            if (acceptCached(id, hidden.get(id), values.get(id), assembly)) { release(held, id); }
        }
        if (held.isEmpty()) { return; }
        var load = new ArrayList<>(held.keySet());
        cache.record("source");
        var loaded = reader.lists(load);
        log.debug("文章列表批量回源 count={}", load.size());
        for (long id : load) {
            var value = Objects.requireNonNull(loaded.get(id), "SQL批量项未确认");
            boolean published;
            try {
                published = switch (value.state()) {
                    case PUBLIC -> lists.publish(id, held.get(id), value.data());
                    case MISSING -> cache.missing(id, held.get(id));
                    case HIDDEN -> cache.hide(id, held.get(id));
                };
            } finally { release(held, id); }
            if (!published) {
                log.debug("文章列表回填被拒 articleId={}", id);
                continue;
            }
            if (value.state() == LoadedArticle.State.PUBLIC) { assembly.result.put(id, value.data()); }
            assembly.pending.remove(id);
        }
    }

    /** 从待清理集合移除后立刻解锁，异常路径只处理尚未释放的实体。 */
    private void release(Map<Long, String> held, long id) { cache.release(id, held.remove(id)); }

    /** 单次装配的局部结果，不能作为跨请求缓存或操作状态保存。 */
    private static final class Assembly {
        private final Map<Long, ArticleBriefData> result = new HashMap<>();
        private final Set<Long> pending;
        private Assembly(List<Long> ids) {
            pending = new LinkedHashSet<>(ids);
        }
    }
}
