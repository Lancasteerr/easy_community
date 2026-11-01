package com.febrie.backend.article.infrastructure.cache;

import com.febrie.backend.article.application.dto.*;
import com.febrie.backend.article.infrastructure.config.ArticleCacheProperties;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真Redis验证Lua与租期协议；每次随机前缀，不执行FLUSHDB或SCRIPT FLUSH。 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS", matches = "true")
class ArticleCacheIntegrationTests {
    private final String namespace = "ec:test:" + UUID.randomUUID() + ":article:v2";
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private ArticleCache cache;
    private ArticleDetailCacheStore details;
    private ArticleListCacheStore lists;
    @BeforeEach void connect() {
        var server = new RedisStandaloneConfiguration(System.getenv().getOrDefault("IT_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
        connections = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        cache = new ArticleCache(redis, new ArticleCacheProperties(namespace));
        details = new ArticleDetailCacheStore(cache, new ObjectMapper());
        lists = new ArticleListCacheStore(cache, new ObjectMapper());
    }
    @AfterEach void clean() {
        if (redis != null) {
            try (var keys = redis.scan(ScanOptions.scanOptions().match(namespace + ":*").count(100).build())) {
                while (keys.hasNext()) { redis.delete(keys.next()); }
            }
        }
        if (connections != null) { connections.destroy(); }
    }
    @Test void storesBothProjectionsAndDoesNotRenewTtlOnHit() {
        String token = cache.tryLock(1);
        assertNotNull(token);
        assertTrue(details.publish(1, token, detail()));
        assertTrue(lists.publish(1, token, list()));
        long ttl = redis.getExpire(cache.key(1, "detail"), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl <= 300_000 && ttl >= 269_000);
        redis.expire(cache.key(1, "detail"), Duration.ofSeconds(30));
        assertEquals("https://cover.test/1", details.get(1).data().coverUrl());
        assertEquals("author", lists.get(List.of(1L)).get(1L).data().authorName());
        assertTrue(redis.getExpire(cache.key(1, "detail")) <= 30);
        assertTrue(cache.unlock(1, token));
    }
    @Test void expiredTokenCannotPublishInvalidateHideMissingOrUnlockNewOwner() {
        String old = cache.tryLock(1);
        redis.delete(cache.key(1, "cache-lock")); // 可控地模拟租期已过，不用sleep。
        String current = cache.tryLock(1);
        assertNotEquals(old, current);
        assertFalse(details.publish(1, old, detail()));
        assertFalse(lists.publish(1, old, list()));
        assertFalse(cache.missing(1, old));
        assertFalse(cache.invalidate(1, old));
        assertFalse(cache.hide(1, old));
        assertFalse(cache.unlock(1, old));
        assertTrue(cache.hide(1, current));
        assertTrue(cache.hidden(1));
        assertTrue(cache.unlock(1, current));
    }
    @Test void lateInvalidationNeverDeletesTombstoneAndBothNegativeEntriesAreCleared() {
        String token = cache.tryLock(1);
        assertTrue(cache.missing(1, token));
        assertEquals(CacheValue.State.NEGATIVE, details.get(1).state());
        assertEquals(CacheValue.State.NEGATIVE, lists.get(List.of(1L)).get(1L).state());
        assertTrue(cache.invalidate(1, token));
        assertEquals(CacheValue.State.MISS, details.get(1).state());
        assertEquals(CacheValue.State.MISS, lists.get(List.of(1L)).get(1L).state());
        assertTrue(cache.hide(1, token));
        assertTrue(cache.invalidate(1, token));
        assertTrue(cache.hidden(1));
        assertEquals(-1, redis.getExpire(cache.key(1, "hidden")));
        assertFalse(details.publish(1, token, detail()));
        assertFalse(lists.publish(1, token, list()));
    }
    @Test void wrongKeyTypeFailsBeforeAnyMutation() {
        String token = cache.tryLock(1);
        redis.opsForList().rightPush(cache.key(1, "list"), "bad");
        assertThrows(RuntimeException.class, () -> cache.hide(1, token));
        assertFalse(cache.hidden(1));
        assertNull(redis.opsForValue().get(cache.key(1, "detail")));
    }
    private static ArticleListView list() {
        return new ArticleListView("1", "title", "2", "author", "summary", "https://cover.test/1",
                List.of(new TagView("3", "Java")), Instant.EPOCH, Instant.EPOCH);
    }
    private static ArticleDetailView detail() {
        return new ArticleDetailView("1", "title", "2", "author", "summary", null, "https://cover.test/1",
                List.of(new TagView("3", "Java")), Instant.EPOCH, Instant.EPOCH, "<p>text</p>");
    }
}
