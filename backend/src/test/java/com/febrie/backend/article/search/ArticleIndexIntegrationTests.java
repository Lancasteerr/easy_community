package com.febrie.backend.article.search;

import com.febrie.backend.article.search.infrastructure.config.SearchConfiguration;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexAdmin;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 随机索引中的真实分词、别名与重复初始化验证，不依赖业务数据。 */
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
class ArticleIndexIntegrationTests {
    @Test void initializesIdempotentlyAndSearchesChineseTags() throws Exception {
        String index = "article_it_" + UUID.randomUUID().toString().replace("-", "");
        var p = SearchTestSupport.properties(Map.of("app.search.index-name", index,
                "app.search.read-alias", index + "_read", "app.search.write-alias", index + "_write"));
        try (var es = new SearchConfiguration().articleElasticsearch(p)) {
            try {
                var admin = new ArticleIndexAdmin(es, p);
                admin.initialize(index, true);
                admin.initialize(index, true);
                es.index(i -> i.index(p.writeAlias()).requireAlias(true).id("1").document(Map.of(
                        "articleId", 1L, "authorId", 9L, "title", "Java 与 Redis 缓存优化实践", "tagIds", List.of("2"),
                        "publishedAt", "2026-11-08T00:00:00Z", "visible", true,
                        "metaVersion", 1L, "rankScore", 1.0, "scoreVersion", 0L)));
                es.indices().refresh(r -> r.index(index));
                var result = es.search(s -> s.index(p.readAlias()).source(x -> x.fetch(false))
                        .query(q -> q.bool(b -> b.must(m -> m.match(t -> t.field("title").query("缓存优化")))
                                .filter(f -> f.term(t -> t.field("tagIds").value("2"))))), Map.class);
                assertEquals(List.of("1"), result.hits().hits().stream().map(h -> h.id()).toList());
                assertNull(result.hits().hits().getFirst().source());
                assertThrows(IllegalStateException.class, () -> new ArticleIndexAdmin(es,
                        SearchTestSupport.properties(Map.of("app.search.read-alias", p.readAlias(),
                                "app.search.write-alias", p.writeAlias()))).initialize(index + "_other", true));
            } finally {
                es.indices().delete(d -> d.index(index, index + "_other").ignoreUnavailable(true));
            }
        }
    }
}
