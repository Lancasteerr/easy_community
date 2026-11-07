package com.febrie.backend.article.search.application;

import com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 必须由无外层事务的同步/维护流程经代理调用，避免文章与Tag读出混合版本。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ArticleIndexReader {
    private final ArticleIndexMapper mapper;
    public ArticleIndexReader(ArticleIndexMapper mapper) { this.mapper = mapper; }
    public ArticleIndexMetadata load(long id) { return load(List.of(id)).get(id); }
    public Map<Long, ArticleIndexMetadata> load(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        var rows = mapper.metadata(ids);
        Map<Long, List<String>> tags = new HashMap<>();
        mapper.tags(ids).forEach(t -> tags.computeIfAbsent(t.articleId(), ignored -> new ArrayList<>()).add(Long.toString(t.tagId())));
        Map<Long, ArticleIndexMetadata> result = new LinkedHashMap<>();
        rows.forEach(r -> result.put(r.articleId(), new ArticleIndexMetadata(r.articleId(), r.title(), r.publishedAt(),
                !r.hidden(), r.metaVersion(), tags.getOrDefault(r.articleId(), List.of()))));
        return result;
    }
}
