package com.febrie.backend.article.infrastructure.cache;

import com.febrie.backend.article.application.dto.ArticleListView;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 每篇文章一个列表投影；批量读取不保存整页顺序，不包含正文。 */
@Component
public class ArticleListCacheStore {
    private final ArticleCache cache;
    private final ObjectMapper mapper;
    public ArticleListCacheStore(ArticleCache cache, ObjectMapper mapper) { this.cache = cache; this.mapper = mapper; }
    public Map<Long, CacheValue<ArticleListView>> get(Collection<Long> ids) {
        Map<Long, CacheValue<ArticleListView>> result = new HashMap<>();
        cache.values(ids, "list").forEach((id, value) -> {
            if (value == null) { result.put(id, CacheValue.miss()); }
            else if (value.equals("M")) { result.put(id, CacheValue.negative()); }
            else if (value.startsWith("D")) { result.put(id, CacheValue.hit(mapper.readValue(value.substring(1), ArticleListView.class))); }
            else { throw new IllegalStateException("文章列表缓存格式无效"); }
        });
        return result;
    }
    public boolean publish(long id, String token, ArticleListView data) {
        return cache.publish(id, token, "list", "D" + mapper.writeValueAsString(data));
    }
}
