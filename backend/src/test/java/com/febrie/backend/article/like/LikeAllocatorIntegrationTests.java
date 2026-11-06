package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import com.febrie.backend.article.like.domain.LikeVersion;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 使用真实Redis校验Lua中INFO的支持、精度、运行栅栏和并发INCR。 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS", matches = "true")
class LikeAllocatorIntegrationTests {
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    LikeVersionAllocator allocator;
    LikeProperties properties;

    @BeforeEach void connect() {
        var server = new RedisStandaloneConfiguration(System.getenv().getOrDefault("IT_REDIS_HOST", "127.0.0.1"), Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
        factory = new LettuceConnectionFactory(server); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        properties = new LikeProperties(true, false, false, "ec:test:" + UUID.randomUUID() + ":like", Duration.ofSeconds(2),
                128, 2, 20, 67108864, 60, 4, Duration.ofMillis(300), "none", -1, 0);
        allocator = new LikeVersionAllocator(redis, properties, mock(LikeTelemetry.class));
    }
    @AfterEach void clean() { if (allocator != null) { redis.delete(allocator.keys()); } if (factory != null) { factory.destroy(); } }

    @Test void rejectsMissingKeysAndSameEpochResetAndUsesRuntimeGuard() {
        assertThrows(ApiException.class, allocator::next);
        String runtime = allocator.recoveryRuntime();
        allocator.initialize(1, runtime); allocator.activate(1, runtime);
        assertTrue(allocator.ready()); assertEquals("0",allocator.status().get(1));
        assertEquals(LikeVersion.pack(1, 1), allocator.next());
        allocator.pause(); assertThrows(ApiException.class, allocator::next);
        assertThrows(RuntimeException.class, () -> allocator.initialize(1, runtime));
        allocator.activate(1, runtime);
        assertEquals(LikeVersion.pack(1, 2), allocator.next());
        redis.opsForHash().put(allocator.keys().get(2), "runId", "previous-process");
        assertThrows(ApiException.class, allocator::next);
        allocator.pause(); allocator.initialize(2, runtime); allocator.activate(2, runtime);
        assertTrue(allocator.next() > LikeVersion.pack(1, LikeVersion.SEQUENCE_LIMIT - 1));
        assertThrows(RuntimeException.class, () -> allocator.initialize(1, runtime));
    }

    @Test void ttlMissingSequenceAndExhaustionFailClosed() {
        String runtime = allocator.recoveryRuntime(); allocator.initialize(1, runtime); allocator.activate(1, runtime);
        redis.expire(allocator.keys().get(1), Duration.ofHours(1));
        assertThrows(ApiException.class, allocator::next);
        redis.persist(allocator.keys().get(1));
        redis.opsForValue().set(allocator.keys().get(1), Long.toString(LikeVersion.SEQUENCE_LIMIT - 1));
        assertThrows(ApiException.class, allocator::next);
        assertEquals(Long.toString(LikeVersion.SEQUENCE_LIMIT - 1), redis.opsForValue().get(allocator.keys().get(1)));
        redis.delete(allocator.keys().get(1));
        assertThrows(ApiException.class, allocator::next);
    }

    @Test void concurrentInstancesIssueUniqueVersionsWithoutFloatingPointLoss() throws Exception {
        String runtime = allocator.recoveryRuntime(); allocator.initialize(1024, runtime); allocator.activate(1024, runtime);
        var second = new LikeVersionAllocator(redis, properties, mock(LikeTelemetry.class));
        Set<Long> versions = ConcurrentHashMap.newKeySet();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i=0;i<100;i++) { var selected = i%2 == 0 ? allocator : second; tasks.add(pool.submit(() -> assertTrue(versions.add(selected.next())))); }
            for (var task : tasks) { task.get(10, TimeUnit.SECONDS); }
        }
        assertEquals(100, versions.size());
        assertEquals(LikeVersion.pack(1024, 100), Collections.max(versions));
        assertTrue(Collections.min(versions) > (1L << 53));
    }
}
