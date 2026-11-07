package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 真实PIT在元数据修改后仍保持原候选视图，查询不返回_source。 */
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
class ArticleSearchIndexIntegrationTests {
    @Test void pitKeepsStableCandidatesAndSupportsCombinedFiltering() throws Exception {
        try (var f = new SearchEsFixture()) {
            var store = new ArticleIndexStore(f.es, f.p);
            for (long id = 1; id <= 15; id++) {
                store.upsert(new ArticleIndexMetadata(id, "Java 缓存优化", Instant.ofEpochSecond(id), true, 1, List.of("2")));
            }
            f.es.indices().refresh(r -> r.index(f.index));
            var index = new ArticleSearchIndex(f.es, f.p);
            String pit = index.open();
            try {
                var query = new ArticleSearchQuery("search", "缓存", 2L, "latest", 1);
                var first = index.search(query, pit, List.of()); pit = first.pit();
                assertEquals(15, first.hits().getFirst().articleId());
                store.upsert(new ArticleIndexMetadata(14, "已修改", Instant.ofEpochSecond(14), false, 2, List.of()));
                f.es.indices().refresh(r -> r.index(f.index));
                var second = index.search(query, pit, first.hits().getFirst().sort()); pit = second.pit();
                assertEquals(14, second.hits().getFirst().articleId());
                assertTrue(first.hits().getFirst().sort().size() >= 3);
            } finally { index.close(pit); }
        }
    }
}
