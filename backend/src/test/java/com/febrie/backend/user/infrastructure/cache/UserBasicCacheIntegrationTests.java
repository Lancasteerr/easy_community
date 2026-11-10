package com.febrie.backend.user.infrastructure.cache;

import com.febrie.backend.shared.cache.CacheValue;
import com.febrie.backend.user.application.dto.UserBasicData;
import com.febrie.backend.user.infrastructure.config.UserBasicCacheProperties;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 真实Redis验证token、负缓存与TTL；只清理本次随机命名空间。 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS", matches = "true")
class UserBasicCacheIntegrationTests {
    private final String namespace = "ec:test:" + UUID.randomUUID() + ":user-basic:v1";
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private UserBasicCacheStore cache;

    @BeforeEach void connect() {
        var server = new RedisStandaloneConfiguration(System.getenv().getOrDefault("IT_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
        connections = new LettuceConnectionFactory(server, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
        connections.afterPropertiesSet(); redis = new StringRedisTemplate(connections);
        cache = new UserBasicCacheStore(redis, new UserBasicCacheProperties(namespace), new ObjectMapper(),
                new com.febrie.backend.shared.cache.CacheMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }
    @AfterEach void clean() {
        if (redis != null) {
            try (var keys = redis.scan(ScanOptions.scanOptions().match(namespace + ":*").count(100).build())) {
                while (keys.hasNext()) { redis.delete(keys.next()); }
            }
        }
        if (connections != null) { connections.destroy(); }
    }

    @Test void preservesNullAvatarDoesNotRenewTtlAndInvalidatesBothDataAndNegative() {
        String token = cache.tryLock(1);
        var user = new UserBasicData(1, "deleted-author", null);
        assertTrue(cache.publish(1, token, user));
        long ttl = redis.getExpire(cache.key(1, "data"), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(ttl >= 269_000 && ttl <= 300_000);
        redis.expire(cache.key(1, "data"), Duration.ofSeconds(30));
        assertEquals(user, cache.get(List.of(1L)).get(1L).data());
        assertTrue(redis.getExpire(cache.key(1, "data")) <= 30);
        assertTrue(cache.invalidate(1, token));
        assertEquals(CacheValue.State.MISS, cache.get(List.of(1L)).get(1L).state());
        assertTrue(cache.missing(1, token));
        assertEquals(CacheValue.State.NEGATIVE, cache.get(List.of(1L)).get(1L).state());
        assertTrue(redis.getExpire(cache.key(1, "data")) <= 20);
        assertTrue(cache.invalidate(1, token));
        assertTrue(cache.unlock(1, token));
    }

    @Test void oldTokenCannotMutateOrUnlockNewOwner() {
        String old = cache.tryLock(1);
        redis.delete(cache.key(1, "cache-lock")); // 可控模拟租期失效，无需sleep。
        String current = cache.tryLock(1);
        assertFalse(cache.publish(1, old, new UserBasicData(1, "old", null)));
        assertFalse(cache.missing(1, old));
        assertFalse(cache.invalidate(1, old));
        assertFalse(cache.unlock(1, old));
        assertTrue(cache.publish(1, current, new UserBasicData(1, "current", null)));
        assertEquals("current", cache.get(List.of(1L)).get(1L).data().username());
        assertTrue(cache.unlock(1, current));
    }

    @Test void wrongTypeAndCorruptPayloadDoNotBecomeNegative() {
        String token = cache.tryLock(1);
        redis.opsForList().rightPush(cache.key(1, "data"), "wrong-type");
        assertThrows(RuntimeException.class, () -> cache.invalidate(1, token));
        assertEquals(token, redis.opsForValue().get(cache.key(1, "cache-lock")));
        redis.delete(cache.key(1, "data"));
        redis.opsForValue().set(cache.key(1, "data"), "D{broken");
        assertThrows(RuntimeException.class, () -> cache.get(List.of(1L)));
    }
}
