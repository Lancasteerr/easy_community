package com.febrie.backend.article.application;

import com.febrie.backend.article.infrastructure.cache.ArticleViewStore;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 统计失败降级SQL；无外层事务，SQL基线查询返回后才继续访问Redis。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleViewService {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Logger log = LoggerFactory.getLogger(ArticleViewService.class);
    private final ArticleViewStore store;
    private final ArticleViewReader reader;
    private final Clock clock;

    public ArticleViewService(ArticleViewStore store, ArticleViewReader reader, Clock clock) {
        this.store = store; this.reader = reader; this.clock = clock;
    }

    public long record(long articleId) {
        LocalDate date = LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
        Long value;
        try {
            value = store.increment(articleId, date, null);
        } catch (RuntimeException failure) {
            // 结果未知也不重发加一，只查询SQL用于本次展示。
            return fallback(articleId, failure);
        }
        if (value != null) { return value; }
        // SQL失败直接传播，不能把SQL依赖错误误判为Redis失败再次查询。
        long baseline = reader.total(articleId);
        try { return Objects.requireNonNull(store.increment(articleId, date, baseline), "提供基线后计数未完成"); }
        catch (RuntimeException failure) { return fallback(articleId, failure); }
    }

    private long fallback(long articleId, RuntimeException failure) {
        log.warn("文章浏览计数失败，回退SQL articleId={} error={}", articleId, failure.getClass().getSimpleName());
        return reader.total(articleId);
    }

    public Map<Long, Long> totals(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        Map<Long, Long> values;
        try { values = new LinkedHashMap<>(store.totals(ids)); }
        catch (RuntimeException failure) {
            log.warn("读取实时浏览量失败，回退SQL error={}", failure.getClass().getSimpleName());
            return reader.totals(ids);
        }
        List<Long> missing = ids.stream().filter(id -> !values.containsKey(id)).distinct().toList();
        if (!missing.isEmpty()) {
            Map<Long, Long> baselines = reader.totals(missing);
            try { values.putAll(store.initialize(baselines)); }
            catch (RuntimeException failure) {
                log.warn("初始化实时浏览量失败，使用SQL基线 error={}", failure.getClass().getSimpleName());
                values.putAll(baselines);
            }
        }
        return values;
    }
}
