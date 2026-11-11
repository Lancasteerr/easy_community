package com.febrie.backend.article.search.application;

import com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper;
import com.febrie.backend.article.common.ArticleTagIds;
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
        Map<Long, ArticleIndexMetadata> result = new LinkedHashMap<>();
        rows.forEach(r -> result.put(r.articleId(), new ArticleIndexMetadata(r.articleId(), r.authorId(), r.title(), r.publishedAt(),
                !r.hidden(), r.metaVersion(), ArticleTagIds.decode(r.tagIdsJson()).stream().map(String::valueOf).toList())));
        return result;
    }
}
