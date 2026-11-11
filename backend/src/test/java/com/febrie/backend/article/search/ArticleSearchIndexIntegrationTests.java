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
                store.upsert(new ArticleIndexMetadata(id, id % 2 == 1 ? 9L : 10L, "Java 缓存优化", Instant.ofEpochSecond(id), true, 1, List.of("2")));
            }
            f.es.indices().refresh(r -> r.index(f.index));
            var index = new ArticleSearchIndex(f.es, f.p);
            String pit = index.open();
            try {
                var query = new ArticleSearchQuery("search", "缓存", 2L, 9L, "latest", 1);
                var first = index.search(query, pit, List.of()); pit = first.pit();
                assertEquals(15, first.hits().getFirst().articleId());
                store.upsert(new ArticleIndexMetadata(13, 9L, "已修改", Instant.ofEpochSecond(14), false, 2, List.of()));
                f.es.indices().refresh(r -> r.index(f.index));
                var second = index.search(query, pit, first.hits().getFirst().sort()); pit = second.pit();
                assertEquals(13, second.hits().getFirst().articleId());
                assertTrue(first.hits().getFirst().sort().size() >= 3);
                // 仅按作者筛选也合法，新PIT排除已隐藏文章与其他作者。
                String fresh = index.open();
                try {
                    var authorOnly = index.search(new ArticleSearchQuery("search", null, null, 9L, null, 20), fresh, List.of());
                    fresh = authorOnly.pit();
                    assertEquals(List.of(15L, 11L, 9L, 7L, 5L, 3L, 1L), authorOnly.hits().stream().map(ArticleSearchIndex.Hit::articleId).toList());
                } finally { index.close(fresh); }
            } finally { index.close(pit); }
        }
    }
}
