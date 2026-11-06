package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.ArticleListView;
import com.febrie.backend.article.content.application.dto.LoadedArticle;
import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.content.infrastructure.cache.ArticleListCacheStore;
import com.febrie.backend.article.content.infrastructure.cache.CacheValue;

import java.util.*;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 装配已由主库选出的本人公开文章；批量查询不把异常项静默当作删除。 */
@Component
@Transactional(propagation = Propagation.NEVER)
public class ArticleListHydrator {
    private static final Logger log = LoggerFactory.getLogger(ArticleListHydrator.class);
    private final ArticleCache cache;
    private final ArticleListCacheStore lists;
    private final ArticleReader reader;
    public ArticleListHydrator(ArticleCache cache, ArticleListCacheStore lists, ArticleReader reader) {
        this.cache = cache; this.lists = lists; this.reader = reader;
    }

    public List<ArticleListView> authorPage(long author, List<Long> ids) {
        if (ids.isEmpty()) { return List.of(); }
        Assembly assembly = new Assembly(ids);
        long deadline = System.nanoTime() + ArticleCache.WAIT_BUDGET.toNanos();
        while (!assembly.pending.isEmpty()) {
            var hidden = cache.hidden(assembly.pending);
            var values = lists.get(assembly.pending);
            Set<Long> misses = new LinkedHashSet<>();
            for (long id : new ArrayList<>(assembly.pending)) {
                if (!acceptCached(id, hidden.get(id), values.get(id), assembly)) { misses.add(id); }
            }

            Map<Long, String> held = new LinkedHashMap<>();
            try {
                // 所有锁仅非阻塞尝试，不能持有A锁阻塞等待B锁。
                for (long id : misses) {
                    String token = cache.tryLock(id);
                    if (token != null) { held.put(id, token); }
                }
                if (!held.isEmpty()) { loadLocked(held, assembly); }
            } finally { held.forEach(cache::release); }
            if (!assembly.pending.isEmpty()) {
                if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) { throw ArticleErrors.unavailable(); }
                LockSupport.parkNanos(Math.min(25_000_000, Math.max(0, deadline - System.nanoTime())));
            }
        }

        // 返回前再查墓碑；与已授权分页SQL矛盾的项由主库批量核对，不绕锁写共享缓存。
        cache.hidden(assembly.result.keySet()).forEach((id, hidden) -> {
            if (hidden) { assembly.conflicts.add(id); assembly.result.remove(id); }
        });
        if (!assembly.conflicts.isEmpty()) {
            var checked = reader.lists(assembly.conflicts);
            for (long id : assembly.conflicts) {
                var value = Objects.requireNonNull(checked.get(id), "SQL核对项未确认");
                if (value.state() == LoadedArticle.State.PUBLIC) { assembly.result.put(id, value.data()); }
            }
        }
        for (var value : assembly.result.values()) {
            if (!Long.toString(author).equals(value.authorId())) { throw ArticleErrors.unavailable(); }
        }
        return ids.stream().map(assembly.result::get).filter(Objects::nonNull).toList();
    }

    private boolean acceptCached(long id, boolean hidden, CacheValue<ArticleListView> value, Assembly assembly) {
        if (hidden || value.state() == CacheValue.State.NEGATIVE) { assembly.conflicts.add(id); }
        else if (value.state() == CacheValue.State.HIT) { assembly.result.put(id, value.data()); }
        else { return false; }
        assembly.pending.remove(id);
        return true;
    }

    /** 进入时已持有这些文章的锁；二次检查后只合并仍缺失的项回源。 */
    private void loadLocked(Map<Long, String> held, Assembly assembly) {
        var hidden = cache.hidden(held.keySet());
        var values = lists.get(held.keySet());
        List<Long> load = new ArrayList<>();
        for (long id : held.keySet()) {
            if (!acceptCached(id, hidden.get(id), values.get(id), assembly)) { load.add(id); }
        }
        if (load.isEmpty()) { return; }
        var loaded = reader.lists(load);
        log.debug("文章列表批量回源 count={}", load.size());
        for (long id : load) {
            var value = Objects.requireNonNull(loaded.get(id), "SQL批量项未确认");
            boolean published = switch (value.state()) {
                case PUBLIC -> lists.publish(id, held.get(id), value.data());
                case MISSING -> cache.missing(id, held.get(id));
                case HIDDEN -> cache.hide(id, held.get(id));
            };
            if (!published) { log.debug("文章列表回填被拒 articleId={}", id); }
            if (value.state() == LoadedArticle.State.PUBLIC) { assembly.result.put(id, value.data()); }
            assembly.pending.remove(id);
        }
    }

    /** 单次装配的局部结果，不能作为跨请求缓存或操作状态保存。 */
    private static final class Assembly {
        private final Map<Long, ArticleListView> result = new HashMap<>();
        private final Set<Long> pending;
        private final Set<Long> conflicts = new HashSet<>();
        private Assembly(List<Long> ids) { pending = new LinkedHashSet<>(ids); }
    }
}
