package com.febrie.backend.article.content.infrastructure.cache;

import com.febrie.backend.shared.cache.CacheValue;
import com.febrie.backend.article.content.application.dto.ArticleBriefData;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 每篇文章一个列表投影；批量读取不保存整页顺序，不包含正文。 */
@Component
public class ArticleListCacheStore {
    private final ArticleCache cache;
    private final ObjectMapper mapper;
    public ArticleListCacheStore(ArticleCache cache, ObjectMapper mapper) { this.cache = cache; this.mapper = mapper; }
    public Map<Long, CacheValue<ArticleBriefData>> get(Collection<Long> ids) {
        Map<Long, CacheValue<ArticleBriefData>> result = new HashMap<>();
        cache.values(ids, "list").forEach((id, value) -> result.put(id, decode(id, value)));
        result.values().forEach(value -> cache.record(switch (value.state()) {
            case HIT -> "hit"; case NEGATIVE -> "negative_hit"; case MISS -> "miss";
        }));
        return result;
    }

    /** 与列表查询使用相同的解析规则，损坏的公开投影不会通过校验。 */
    public CacheValue.State visibility(long id) {
        return decode(id, cache.listValueUnlessHidden(id)).state();
    }

    private CacheValue<ArticleBriefData> decode(long id, String value) {
        if (value == null) { return CacheValue.miss(); }
        if (value.equals("M")) { return CacheValue.negative(); }
        if (value.startsWith("D")) {
            var article = mapper.readValue(value.substring(1), ArticleBriefData.class);
            if (article != null && Long.toString(id).equals(article.articleId())) { return CacheValue.hit(article); }
        }
        throw new IllegalStateException("文章列表缓存格式无效");
    }

    public boolean publish(long id, String token, ArticleBriefData data) {
        return cache.publish(id, token, "list", "D" + mapper.writeValueAsString(data));
    }
}
