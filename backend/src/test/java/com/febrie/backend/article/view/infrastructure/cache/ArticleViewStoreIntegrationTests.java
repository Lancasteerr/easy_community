package com.febrie.backend.article.view.infrastructure.cache;

import com.febrie.backend.article.view.application.ArticleViewSqlService.FlushResult;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真Redis验证双Hash Lua；仅清理本次随机命名空间，不清空共享数据库。 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS", matches = "true")
class ArticleViewStoreIntegrationTests {
    private final String prefix = "ec:test:views:" + UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 11, 2);
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private ArticleViewStore store;

    @BeforeEach void connect() {
        var server = new RedisStandaloneConfiguration(System.getenv().getOrDefault("IT_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
        connections = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        store = new ArticleViewStore(redis, prefix);
    }

    @AfterEach void clean() {
        if (redis != null) {
            try (var keys = redis.scan(ScanOptions.scanOptions().match("{" + prefix + ":article:stats:v1}:*").count(100).build())) {
                while (keys.hasNext()) { redis.delete(keys.next()); }
            }
        }
        if (connections != null) { connections.destroy(); }
    }

    @Test void missingBaselineDoesNotWriteAndTtlIsNotRenewed() {
        assertNull(store.increment(1, date, null));
        assertFalse(redis.hasKey(store.dailyKey(date)));
        assertFalse(redis.hasKey(store.totalKey()));
        assertEquals(101L, store.increment(1, date, 100L));
        assertTrue(redis.getExpire(store.dailyKey(date)) > 172790);
        assertEquals(-1L, redis.getExpire(store.totalKey()));
        redis.expire(store.dailyKey(date), Duration.ofSeconds(30));
        assertEquals(102L, store.increment(1, date, null));
        assertTrue(redis.getExpire(store.dailyKey(date)) <= 30);
        assertEquals(103L, store.increment(1, date.plusDays(1), null));
        assertEquals(Map.of(1L, 2L), store.daily(date));
        assertEquals(Map.of(1L, 1L), store.daily(date.plusDays(1)));
    }

    @Test void batchInitializationNeverOverwritesConcurrentTotalsOrCountsVisits() {
        assertEquals(Map.of(1L, 100L, 2L, 200L), store.initialize(Map.of(1L, 100L, 2L, 200L)));
        store.increment(1, date, null);
        assertEquals(Map.of(1L, 101L, 3L, 0L), store.initialize(Map.of(1L, 50L, 3L, 0L)));
        assertEquals(Map.of(1L, 101L, 2L, 200L, 3L, 0L), store.totals(List.of(1L, 2L, 3L, 4L)));
        assertEquals(Map.of(1L, 1L), store.daily(date));
    }

    @Test void repairRaisesFloorsWithoutAddingDeltaOrRecreatingDailyFields() {
        store.increment(1, date, 9L);
        var result = new FlushResult(Map.of(1L, 100L), Map.of(1L, 1000L, 2L, 20L));
        store.repair(date, result);
        store.repair(date, result);
        assertEquals(Map.of(1L, 100L), store.daily(date));
        assertEquals(Map.of(1L, 1000L, 2L, 20L), store.totals(List.of(1L, 2L)));
        redis.opsForHash().put(store.dailyKey(date), "1", "105");
        redis.opsForHash().put(store.totalKey(), "1", "1010");
        store.repair(date, result);
        assertEquals(Map.of(1L, 105L), store.daily(date));
        assertEquals(Map.of(1L, 1010L), store.totals(List.of(1L)));
        redis.delete(store.dailyKey(date));
        store.repair(date, result);
        assertFalse(redis.hasKey(store.dailyKey(date)));
    }

    @Test void wrongTypesMalformedCountsAndOverflowAreRejectedBeforeWriting() {
        redis.opsForValue().set(store.dailyKey(date), "wrong-type");
        assertThrows(RuntimeException.class, () -> store.increment(1, date, 0L));
        assertFalse(redis.hasKey(store.totalKey()));
        redis.delete(store.dailyKey(date));
        redis.opsForValue().set(store.totalKey(), "wrong-type");
        assertThrows(RuntimeException.class, () -> store.increment(1, date, 0L));
        assertFalse(redis.hasKey(store.dailyKey(date)));
        redis.delete(store.totalKey());
        for (String invalid : List.of("bad", "-1", "01", "9223372036854775807")) {
            redis.opsForHash().put(store.dailyKey(date), "1", invalid);
            assertThrows(RuntimeException.class, () -> store.increment(1, date, 0L));
            assertFalse(redis.hasKey(store.totalKey()));
        }
        redis.delete(store.dailyKey(date));
        redis.opsForHash().put(store.totalKey(), "1", Long.toString(Long.MAX_VALUE));
        assertThrows(RuntimeException.class, () -> store.increment(1, date, null));
        assertFalse(redis.hasKey(store.dailyKey(date)));
    }

    @Test void bigintAboveDoublePrecisionIsPreserved() {
        long large = 9_007_199_254_740_993L;
        assertEquals(large + 1, store.increment(1, date, large));
        store.repair(date, new FlushResult(Map.of(1L, large + 3), Map.of(1L, large + 5)));
        assertEquals(Map.of(1L, large + 3), store.daily(date));
        assertEquals(Map.of(1L, large + 5), store.totals(List.of(1L)));
        assertEquals(large + 6, store.increment(1, date, null));
    }

    @Test void concurrentInitializationAndCountingGrowBothHashesExactlyOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < 80; i++) {
                tasks.add(executor.submit(() -> {
                    store.initialize(Map.of(1L, 100L));
                    assertNotNull(store.increment(1, date, 100L));
                    store.repair(date, new FlushResult(Map.of(1L, 0L), Map.of(1L, 100L)));
                }));
            }
            for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
        }
        assertEquals(Map.of(1L, 80L), store.daily(date));
        assertEquals(Map.of(1L, 180L), store.totals(List.of(1L)));
    }

    @Test void malformedDailyItemsAreSkippedIndividually() {
        redis.opsForHash().putAll(store.dailyKey(date), Map.of("1", "2", "bad", "5", "2", "-1", "3", "9223372036854775808"));
        assertEquals(Map.of(1L, 2L), store.daily(date));
    }
}
