package com.febrie.backend.performance;

import com.febrie.backend.article.view.application.*;
import com.febrie.backend.article.view.infrastructure.cache.ArticleViewStore;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** 缓存/PV对照与真实索引同步性能；输出完整样本，不预设优化必然更快。 */
@EnabledIfEnvironmentVariable(named = "IT_RESUME_PERF", matches = "true")
class ResumePerformanceTests {
    @Test void measureCachePvAndIndex() throws Exception {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("IT_PERF_SECONDS", "60"));
        int round = Integer.parseInt(System.getenv().getOrDefault("IT_PERF_ROUND", "1"));
        assertTrue(seconds >= 3 && seconds <= 600);
        List<Map<String, Object>> stages = new ArrayList<>();
        try (var fixture = new PerfFixture()) {
            var report = new LinkedHashMap<String, Object>();
            report.put("recordedAt", Instant.now().toString()); report.put("round", round);
            report.put("java", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
            report.put("datasetArticles", fixture.ids.size()); report.put("authors", fixture.users.size());
            report.put("pageSize", 20); report.put("bodyCharacters", 4096); report.put("stages", stages);
            report.put("mysql", fixture.jdbc.queryForObject("SELECT VERSION()", String.class));
            report.put("mysqlDurability", fixture.jdbc.queryForMap("SELECT @@innodb_flush_log_at_trx_commit AS flushAtCommit, @@sync_binlog AS syncBinlog"));
            report.put("qualification", "同机HTTP基准；缓存/PV为测试专用对照入口，feed/文章发布为实际业务入口；不代表极限容量");
            try {
                // 验证输出完全一致后再预热；对照顺序逐轮交替，减少固定先后顺序的偏差。
                assertEquals(fixture.get("/api/perf/cards/source").body(), fixture.get("/api/perf/cards/cached").body());
                for (int i = 0; i < 100; i++) {
                    assertEquals(200, fixture.get("/api/perf/cards/source").statusCode());
                    assertEquals(200, fixture.get("/api/perf/cards/cached").statusCode());
                }
                for (String mode : round % 2 == 1 ? List.of("source", "cached") : List.of("cached", "source")) {
                    var counter = fixture.app.getBean(PerfSqlCounter.class); var before = counter.snapshot();
                    var stage = PerfLoad.run("cache_" + mode, 80, seconds, 200,
                            number -> fixture.get("/api/perf/cards/" + mode).statusCode());
                    stage.put("sqlStatements", counter.delta(before)); stages.add(stage);
                }
                // 实际游客feed不带登录Cookie，避免把鉴权SQL混入公开读取指标。
                try (var anonymous = java.net.http.HttpClient.newHttpClient()) {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(fixture.base + "/api/articles/feed"))
                            .timeout(Duration.ofSeconds(10)).GET().build();
                    for (int i = 0; i < 50; i++) { assertEquals(200, anonymous.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()); }
                    var counter = fixture.app.getBean(PerfSqlCounter.class); var before = counter.snapshot();
                    var stage = PerfLoad.run("feed_http", 80, seconds, 200,
                            number -> anonymous.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
                    stage.put("sqlStatements", counter.delta(before)); stages.add(stage);
                }
                for (String mode : round % 2 == 1 ? List.of("sql", "redis") : List.of("redis", "sql")) {
                    stages.add(pv(fixture, mode, seconds));
                }
                stages.add(index(fixture, seconds));
                report.put("passed", true);
            } finally {
                // 中途失败仍保存已完成阶段，但只有passed=true才是整轮通过。
                Files.writeString(PerfLoad.output().resolve("core.json"), fixture.json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            }
        }
    }

    private Map<String, Object> pv(PerfFixture f, String mode, int seconds) throws Exception {
        var flush = f.app.getBean(ArticleViewFlush.class);
        var store = f.app.getBean(ArticleViewStore.class);
        var redis = f.app.getBean(StringRedisTemplate.class);
        var ids = f.ids.subList(0, 20);
        flush.flush();
        LocalDate date = LocalDate.now(ArticleViewService.BUSINESS_ZONE);
        for (long id : ids) {
            f.jdbc.update("INSERT IGNORE INTO article_view_stat(article_id,stat_date,pv) VALUES(?,?,0)", id, date);
            long daily = f.jdbc.queryForObject("SELECT pv FROM article_view_stat WHERE article_id=? AND stat_date=?", Long.class, id, date);
            long total = f.jdbc.queryForObject("SELECT view_count FROM article WHERE id=?", Long.class, id);
            // A/B之间恢复相同的累计基线，避免同步基线写库后Redis旧值导致漏计。
            redis.opsForHash().put(store.dailyKey(date), Long.toString(id), Long.toString(daily));
            redis.opsForHash().put(store.totalKey(), Long.toString(id), Long.toString(total));
        }
        redis.expire(store.dailyKey(date), Duration.ofDays(2));
        for (int i = 0; i < 100; i++) { assertEquals(200, f.get("/api/perf/pv/" + mode + "/" + ids.get(i % ids.size())).statusCode()); }
        flush.flush();
        long initial = sum(f, ids);
        var counter = f.app.getBean(PerfSqlCounter.class); var before = counter.snapshot();
        var result = PerfLoad.run("pv_" + mode, 120, seconds, 200,
                number -> f.get("/api/perf/pv/" + mode + "/" + ids.get(number % ids.size())).statusCode());
        long flushStarted = System.nanoTime(); flush.flush();
        result.put("finalFlushMs", (System.nanoTime() - flushStarted) / 1_000_000.0);
        result.put("sqlStatementsIncludingFinalFlush", counter.delta(before));
        long persisted = sum(f, ids) - initial;
        result.put("persistedIncrement", persisted); result.put("sqlCountMatchesSuccess", persisted == ((Number) result.get("success")).longValue());
        assertEquals(((Number) result.get("success")).longValue(), persisted, "PV最终增量必须等于成功请求数");
        long writes = counter.delta(before).entrySet().stream().filter(entry -> entry.getKey().endsWith(".incrementTotal") || entry.getKey().endsWith(".upsertPv"))
                .mapToLong(Map.Entry::getValue).sum();
        result.put("pvWriteStatements", writes);
        return result;
    }

    private long sum(PerfFixture f, List<Long> ids) {
        return ids.stream().mapToLong(id -> f.jdbc.queryForObject("SELECT view_count FROM article WHERE id=?", Long.class, id)).sum();
    }

    private Map<String, Object> index(PerfFixture f, int seconds) throws Exception {
        String body = "{\"title\":\"异步索引性能验证\",\"content\":{\"schemaVersion\":1,\"doc\":{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"正文\"}]}]}},\"coverMediaId\":null,\"tagIds\":[]}";
        var durations = new ConcurrentLinkedQueue<Long>();
        var failures = new ConcurrentLinkedQueue<String>();
        var samples = new ConcurrentLinkedQueue<String>();
        var index = f.app.getBean(ArticleIndexStore.class);
        Map<String, Object> result;
        try (var observers = Executors.newVirtualThreadPerTaskExecutor()) {
            result = PerfLoad.run("index_publish_http", 5, seconds, 200, number -> {
                long started = System.nanoTime();
                var response = f.post("/api/articles", body);
                if (response.statusCode() == 200) {
                    long id = f.json.readTree(response.body()).path("articleId").asLong();
                    observers.submit(() -> {
                        try {
                            long deadline = started + Duration.ofSeconds(30).toNanos();
                            while (index.metaVersion(id) < 1) {
                                if (System.nanoTime() >= deadline) { throw new IllegalStateException("索引同步超时"); }
                                Thread.sleep(50);
                            }
                            long duration = System.nanoTime() - started;
                            durations.add(duration); samples.add(id + "," + duration);
                        } catch (Exception failure) { failures.add(failure.getClass().getSimpleName()); }
                    });
                }
                return response.statusCode();
            });
        }
        result.put("observedIndexCompletions", durations.size()); result.put("observationErrors", failures.size());
        var sorted = durations.stream().sorted().toList();
        result.put("indexObservedP50Ms", PerfLoad.percentile(sorted, .5));
        result.put("indexObservedP95Ms", PerfLoad.percentile(sorted, .95));
        result.put("indexObservedP99Ms", PerfLoad.percentile(sorted, .99));
        result.put("observation", "请求开始至ES实时GET读到metaVersion>=1；轮询50ms，包含观察开销，不是搜索refresh可见延迟");
        Files.writeString(PerfLoad.output().resolve("index-observed.csv"), "articleId,observedLatencyNanos\n" + String.join("\n", samples));
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(((Number) result.get("success")).longValue(), durations.size());
        return result;
    }
}
