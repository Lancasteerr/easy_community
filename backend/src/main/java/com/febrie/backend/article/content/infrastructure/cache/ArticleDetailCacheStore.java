package com.febrie.backend.article.content.infrastructure.cache;

import com.febrie.backend.shared.cache.CacheValue;
import com.febrie.backend.article.content.application.dto.ArticleDetailData;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 详情DATA只含文章字段；作者资料和地址在解锁后组装。 */
@Component
public class ArticleDetailCacheStore {
    private final ArticleCache cache;
    private final ObjectMapper mapper;
    public ArticleDetailCacheStore(ArticleCache cache, ObjectMapper mapper) { this.cache = cache; this.mapper = mapper; }
    public CacheValue<ArticleDetailData> get(long id) {
        String value = cache.value(id, "detail");
        if (value == null) { cache.record("miss"); return CacheValue.miss(); }
        if (value.equals("M")) { cache.record("negative_hit"); return CacheValue.negative(); }
        if (!value.startsWith("D")) { throw new IllegalStateException("文章详情缓存格式无效"); }
        var data = mapper.readValue(value.substring(1), ArticleDetailData.class);
        cache.record("hit");
        return CacheValue.hit(data);
    }
    public boolean publish(long id, String token, ArticleDetailData data) {
        return cache.publish(id, token, "detail", "D" + mapper.writeValueAsString(data));
    }
}
