package com.febrie.backend.shared.ratelimit;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import com.febrie.backend.auth.infrastructure.ratelimit.RequestLimits;
import com.febrie.backend.auth.infrastructure.session.ActiveSessions;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.domain.Role;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.session.MapSessionRepository;

import static org.junit.jupiter.api.Assertions.*;

/** 仅连接真实 Redis；显式启用，不加载 MySQL、Web 或对象存储配置。 */
@EnabledIfEnvironmentVariable(named = "IT_REDIS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisLuaIntegrationTests {
    private final String prefix = "ec:test:lua:" + UUID.randomUUID();
    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private FixedWindowCounter counter;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:30Z"), ZoneOffset.UTC);
    private RequestLimits limits;
    private ActiveSessions active;

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
        limits = new RequestLimits(redis, properties(), clock, counter);
        active = new ActiveSessions(redis, properties(), new MapSessionRepository(new ConcurrentHashMap<>()), clock);
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

    @Test
    void loginFailuresRefreshBothDimensionsAndPreserveUsernameCase() throws Exception {
        String account = failureKey("account", "Alice_1");
        String ip = failureKey("ip", "192.0.2.1");
        limits.failed("Alice_1", "192.0.2.1");
        assertEquals("1", redis.opsForValue().get(account));
        assertEquals("1", redis.opsForValue().get(ip));
        assertTrue(redis.getExpire(account, TimeUnit.MILLISECONDS) > 955000);
        assertTrue(redis.getExpire(ip, TimeUnit.MILLISECONDS) > 955000);
        redis.expire(account, Duration.ofSeconds(10));
        redis.expire(ip, Duration.ofSeconds(10));
        limits.failed("Alice_1", "192.0.2.1");
        assertTrue(redis.getExpire(account, TimeUnit.MILLISECONDS) > 955000);
        assertTrue(redis.getExpire(ip, TimeUnit.MILLISECONDS) > 955000);
        limits.failed("alice_1", "192.0.2.1");
        assertEquals("2", redis.opsForValue().get(account));
        assertEquals("1", redis.opsForValue().get(failureKey("account", "alice_1")));
        assertEquals("3", redis.opsForValue().get(ip));
    }

    @Test
    void duplicateSessionRegistrationRefreshesTtlAndRemovalStaysRevoked() {
        var principal = principal();
        String key = active.key(principal);
        active.register(principal, "session-id");
        assertTrue(active.contains(principal, "session-id"));
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 7195000);
        redis.expire(key, Duration.ofSeconds(10));
        active.register(principal, "session-id");
        assertEquals(1L, redis.opsForSet().size(key));
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 7195000);
        active.remove(principal, "session-id");
        assertFalse(active.contains(principal, "session-id"));
        assertFalse(redis.hasKey(key));
    }

    @Test
    void concurrentRateLimitCountsRejectionsAndStartsANewFixedWindow() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var attempts = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                attempts.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        limits.consume("probe", "shared", 3);
                        return true;
                    } catch (ApiException denied) {
                        assertEquals(429, denied.status().value());
                        assertEquals(30, denied.retryAfter());
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) { if (attempt.get(10, TimeUnit.SECONDS)) { accepted++; } }
            assertEquals(3, accepted);
        }
        String key = prefix + ":auth:request:probe:" + digest("shared") + ":" + clock.instant().getEpochSecond() / 60;
        assertEquals("12", redis.opsForValue().get(key));
        assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) > 85000);
        var nextWindow = new RequestLimits(redis, properties(), Clock.offset(clock, Duration.ofSeconds(30)), counter);
        assertDoesNotThrow(() -> nextWindow.consume("probe", "shared", 3));
        assertEquals("12", redis.opsForValue().get(key));
        String nextKey = key.substring(0, key.lastIndexOf(':') + 1) + (clock.instant().getEpochSecond() / 60 + 1);
        assertEquals("1", redis.opsForValue().get(nextKey));
    }

    @Test
    void invalidFailureCountersAndSessionTypesNeverReportSuccess() throws Exception {
        // 逐一污染两个维度；只断言失败，不把 Lua 原子执行误当作错误回滚。
        for (String key : new String[]{failureKey("account", "Alice_1"), failureKey("ip", "192.0.2.1")}) {
            redis.delete(key);
            redis.opsForSet().add(key, "wrong-type");
            assertThrows(DataAccessException.class, () -> limits.failed("Alice_1", "192.0.2.1"));
            redis.delete(key);
            for (String invalid : new String[]{"invalid", Long.toString(Long.MAX_VALUE)}) {
                redis.opsForValue().set(key, invalid);
                assertThrows(DataAccessException.class, () -> limits.failed("Alice_1", "192.0.2.1"));
            }
            redis.delete(key);
        }
        redis.opsForValue().set(failureKey("account", "Alice_1"), "invalid");
        assertThrows(DataAccessException.class, () -> limits.checkFailures("Alice_1", "192.0.2.1"));
        redis.opsForValue().set(active.key(principal()), "wrong-type");
        assertThrows(DataAccessException.class, () -> active.register(principal(), "session-id"));
    }

    @Test
    void unavailableRedisNeverReportsSuccessfulWrites() throws Exception {
        // 占用随机端口但不提供 Redis 协议响应，无需停机或修改真实 Redis 服务。
        try (var unavailable = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            var server = new RedisStandaloneConfiguration("127.0.0.1", unavailable.getLocalPort());
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(200)).build();
            var brokenConnections = new LettuceConnectionFactory(server, client);
            brokenConnections.afterPropertiesSet();
            try {
                var brokenRedis = new StringRedisTemplate(brokenConnections);
                var brokenCounter = new FixedWindowCounter(brokenRedis);
                var brokenLimits = new RequestLimits(brokenRedis, properties(), clock, brokenCounter);
                var brokenSessions = new ActiveSessions(brokenRedis, properties(),
                        new MapSessionRepository(new ConcurrentHashMap<>()), clock);
                assertThrows(DataAccessException.class,
                        () -> brokenCounter.increment(prefix + ":unavailable", Duration.ofSeconds(60)));
                assertThrows(DataAccessException.class, () -> brokenLimits.failed("Alice_1", "192.0.2.1"));
                assertThrows(DataAccessException.class, () -> brokenSessions.register(principal(), "session-id"));
            } finally {
                brokenConnections.destroy();
            }
        }
    }

    private AuthProperties properties() {
        return new AuthProperties("test", prefix, false, Duration.ofHours(2),
                60, 30, 120, 5, 30, Duration.ofMinutes(10));
    }

    private SessionPrincipal principal() {
        return new SessionPrincipal(1, "Alice_1", Role.USER, 1, clock.instant(), clock.instant().plusSeconds(7200));
    }

    private String failureKey(String kind, String value) throws Exception {
        return prefix + ":auth:login-fail:" + kind + ":" + digest(value) + ":" + clock.instant().getEpochSecond() / 900;
    }

    private String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
