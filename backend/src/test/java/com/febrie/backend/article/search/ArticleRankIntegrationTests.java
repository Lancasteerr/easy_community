package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Bulk局部更新、404、不建半文档、分数下降与元数据并发隔离。 */
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
class ArticleRankIntegrationTests {
    @Test void bulkDoesNotCreateMissingDocumentsAndNewerScoreMayDecrease() throws Exception {
        try (var f = new SearchEsFixture()) {
            var meta = new ArticleIndexStore(f.es, f.p);
            meta.upsert(metadata(1, true));
            var ranks = new ArticleRankStore(f.es, new SimpleMeterRegistry());
            var result = ranks.write(f.p.writeAlias(), true, List.of(new ArticleRankStore.Score(1, 100, 10), new ArticleRankStore.Score(2, 100, 10)));
            assertEquals(1, result.missing()); assertEquals(0, result.failed());
            assertFalse(f.es.get(g -> g.index(f.index).id("2"), Map.class).found());
            ranks.write(f.p.writeAlias(), true, List.of(new ArticleRankStore.Score(1, 1, 11)));
            ranks.write(f.p.writeAlias(), true, List.of(new ArticleRankStore.Score(1, 999, 9)));
            meta.upsert(metadata(2, false));
            var value = f.es.get(g -> g.index(f.index).id("1"), Map.class).source();
            assertEquals(1, ((Number) value.get("rankScore")).doubleValue());
            assertEquals(11, ((Number) value.get("scoreVersion")).longValue());
            assertEquals(false, value.get("visible")); assertEquals(2, meta.metaVersion(1));
        }
    }
    @Test void concurrentMetaAndScorePreserveBothFieldGroups() throws Exception {
        try (var f = new SearchEsFixture(); var workers = Executors.newFixedThreadPool(2)) {
            var meta = new ArticleIndexStore(f.es, f.p); meta.upsert(metadata(1, true));
            var ranks = new ArticleRankStore(f.es, new SimpleMeterRegistry());
            var barrier = new CyclicBarrier(2);
            var a = workers.submit(() -> { barrier.await(); return meta.upsert(metadata(3, false)); });
            var b = workers.submit(() -> { barrier.await(); return ranks.write(f.p.writeAlias(), true, List.of(new ArticleRankStore.Score(1, 77, 20))); });
            a.get(10, TimeUnit.SECONDS); assertEquals(0, b.get(10, TimeUnit.SECONDS).failed());
            var value = f.es.get(g -> g.index(f.index).id("1"), Map.class).source();
            assertEquals(false, value.get("visible")); assertEquals(3, meta.metaVersion(1));
            assertEquals(77, ((Number) value.get("rankScore")).doubleValue());
        }
    }
    private static ArticleIndexMetadata metadata(long version, boolean visible) {
        return new ArticleIndexMetadata(1, 9L, "文章", Instant.parse("2026-11-01T00:00:00Z"), visible, version, List.of());
    }
}
