package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexStore;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 真正执行Painless创建/乱序/隐藏分支；屏障控制旧快照最后写入。 */
@EnabledIfEnvironmentVariable(named = "IT_SEARCH", matches = "true")
class IndexMetadataIntegrationTests {
    private static ArticleIndexMetadata meta(long version, boolean visible) {
        return new ArticleIndexMetadata(1, "缓存优化v" + version, Instant.parse("2026-11-01T00:00:00Z"), visible, version, List.of("2"));
    }
    @Test void createsCompleteDocumentAndNeverResurrectsHiddenArticle() throws Exception {
        try (var f = new SearchEsFixture()) {
            var store = new ArticleIndexStore(f.es, f.p);
            assertEquals(0, store.metaVersion(1));
            assertTrue(store.upsert(meta(4, true)));
            var initial = f.es.get(g -> g.index(f.index).id("1"), Map.class).source();
            assertEquals(RankFormula.score(0, meta(4, true).publishedAt()), ((Number) initial.get("rankScore")).doubleValue());
            assertEquals(0, ((Number) initial.get("scoreVersion")).longValue());
            f.es.update(u -> u.index(f.p.writeAlias()).id("1").doc(Map.of("rankScore", 123.5, "scoreVersion", 99)), Map.class);
            store.upsert(meta(6, false));
            store.upsert(meta(4, true));
            var current = f.es.get(g -> g.index(f.index).id("1"), Map.class).source();
            assertEquals(6, store.metaVersion(1));
            assertEquals(false, current.get("visible"));
            assertEquals(123.5, ((Number) current.get("rankScore")).doubleValue());
            assertEquals(99, ((Number) current.get("scoreVersion")).longValue());
        }
    }
    @Test void staleSnapshotFinishingLastCannotOverwriteNewVersion() throws Exception {
        try (var f = new SearchEsFixture(); var workers = Executors.newFixedThreadPool(2)) {
            var store = new ArticleIndexStore(f.es, f.p);
            store.upsert(meta(1, true));
            CountDownLatch captured = new CountDownLatch(1), newerWritten = new CountDownLatch(1);
            var old = workers.submit(() -> {
                var snapshot = meta(2, true); captured.countDown();
                if (!newerWritten.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("新版本未完成"); }
                return store.upsert(snapshot);
            });
            assertTrue(captured.await(10, TimeUnit.SECONDS));
            store.upsert(meta(3, false)); newerWritten.countDown(); old.get(10, TimeUnit.SECONDS);
            assertEquals(3, store.metaVersion(1));
            assertEquals(false, f.es.get(g -> g.index(f.index).id("1"), Map.class).source().get("visible"));
        }
    }
}
