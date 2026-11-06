package com.febrie.backend.article.content.infrastructure.cache;

import com.febrie.backend.article.content.application.dto.ArticleDetailView;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 详情DATA自包含展示字段，命中不查媒体、作者或标签。 */
@Component
public class ArticleDetailCacheStore {
    private final ArticleCache cache;
    private final ObjectMapper mapper;
    public ArticleDetailCacheStore(ArticleCache cache, ObjectMapper mapper) { this.cache = cache; this.mapper = mapper; }
    public CacheValue<ArticleDetailView> get(long id) {
        String value = cache.value(id, "detail");
        if (value == null) { return CacheValue.miss(); }
        if (value.equals("M")) { return CacheValue.negative(); }
        if (!value.startsWith("D")) { throw new IllegalStateException("文章详情缓存格式无效"); }
        return CacheValue.hit(mapper.readValue(value.substring(1), ArticleDetailView.class));
    }
    public boolean publish(long id, String token, ArticleDetailView data) {
        return cache.publish(id, token, "detail", "D" + mapper.writeValueAsString(data));
    }
}
