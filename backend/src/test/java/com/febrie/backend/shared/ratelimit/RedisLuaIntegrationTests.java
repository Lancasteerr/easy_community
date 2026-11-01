package com.febrie.backend.shared.ratelimit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** 仅连接真实 Redis；显式启用，不加载 MySQL、Web 或对象存储配置。 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisLuaIntegrationTests {
    private final String prefix = "ec:test:lua:" + UUID.randomUUID();
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private FixedWindowCounter counter;

    @BeforeAll
    void connect() {
        var server = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("IT_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379")));
        server.setPassword(System.getenv().getOrDefault("IT_REDIS_PASSWORD", ""));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build();
        connections = new LettuceConnectionFactory(server, client);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        counter = new FixedWindowCounter(redis);
        try (var connection = connections.getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }

    @AfterEach
    void cleanup() {
        // 只删除本次随机命名空间；不执行 FLUSHDB、SCRIPT FLUSH 或全库 KEYS。
        try (var keys = redis.scan(ScanOptions.scanOptions().match(prefix + ":*").count(100).build())) {
            while (keys.hasNext()) { redis.delete(keys.next()); }
        }
    }

    @AfterAll
    void disconnect() {
        if (connections != null) { connections.destroy(); }
    }

    @Test
    void incrementsRefreshTtlAndPreserveIntegerPrecision() {
        String key = prefix + ":counter";
        assertEquals(1, counter.increment(key, Duration.ofSeconds(60)));
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 55000);
        // 主动缩短 TTL，避免使用 sleep 推断刷新行为。
        redis.expire(key, Duration.ofSeconds(10));
        assertEquals(2, counter.increment(key, Duration.ofSeconds(60)));
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 55000);
        redis.opsForValue().set(key, "9007199254740992");
        assertEquals(9007199254740993L, counter.increment(key, Duration.ofSeconds(60)));
        redis.opsForValue().set(key, Long.toString(Long.MAX_VALUE - 1));
        assertEquals(Long.MAX_VALUE, counter.increment(key, Duration.ofSeconds(60)));
    }

    @Test
    void concurrentIncrementsReturnEveryCountExactlyOnce() throws Exception {
        String key = prefix + ":concurrent";
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = new ArrayList<Future<Long>>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return counter.increment(key, Duration.ofSeconds(60));
                }));
            }
            start.countDown();
            var counts = new ArrayList<Long>();
            for (var result : results) { counts.add(result.get(10, TimeUnit.SECONDS)); }
            assertEquals(java.util.stream.LongStream.rangeClosed(1, 32).boxed().toList(),
                    counts.stream().sorted().toList());
        }
        assertEquals("32", redis.opsForValue().get(key));
        assertTrue(redis.getExpire(key) > 0);
    }

    @Test
    void wrongTypesInvalidIntegersAndOverflowAreFailures() {
        String key = prefix + ":invalid";
        redis.opsForSet().add(key, "member");
        assertThrows(DataAccessException.class, () -> counter.increment(key, Duration.ofSeconds(60)));
        redis.delete(key);
        redis.opsForValue().set(key, "not-an-integer");
        assertThrows(DataAccessException.class, () -> counter.increment(key, Duration.ofSeconds(60)));
        redis.opsForValue().set(key, Long.toString(Long.MAX_VALUE));
        assertThrows(DataAccessException.class, () -> counter.increment(key, Duration.ofSeconds(60)));
        assertEquals(Long.toString(Long.MAX_VALUE), redis.opsForValue().get(key));
    }
}
