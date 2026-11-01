package com.febrie.backend.article.application;

import com.febrie.backend.article.infrastructure.persistence.mapper.ArticleViewStatMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** SQL基线查询在独立代理中结束，调用方之后才能访问Redis。 */
@Service
@Transactional(readOnly = true)
public class ArticleViewReader {
    private final ArticleViewStatMapper stats;

    public ArticleViewReader(ArticleViewStatMapper stats) { this.stats = stats; }

    public long total(long articleId) {
        Long value = stats.selectTotal(articleId);
        if (value == null) { throw ArticleErrors.unavailable(); }
        return value;
    }

    public Map<Long, Long> totals(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        Map<Long, Long> values = new LinkedHashMap<>();
        stats.selectTotals(ids).forEach(row -> values.put(row.getArticleId(), row.getViewCount()));
        // 可见内容已经取得，缺少基线不能静默伪造为零。
        if (!values.keySet().containsAll(ids)) { throw ArticleErrors.unavailable(); }
        return values;
    }
}
