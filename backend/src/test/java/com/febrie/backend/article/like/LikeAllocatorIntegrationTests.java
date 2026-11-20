package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.observability.LikeAllocatorHealth;
import com.febrie.backend.article.like.infrastructure.redis.LikeVersionAllocator;

import com.febrie.backend.article.like.domain.LikeVersion;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 仅用Redis验证真实Lua的重启兼容、维护边界与并发序号，不启动其他业务依赖。 */
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
        properties = new LikeProperties("ec:test:" + UUID.randomUUID() + ":like", Duration.ofSeconds(2),
                128,4, 20, 67108864, 60, Duration.ofMillis(300), "none", -1, 0,20,java.time.Duration.ofMillis(100));
        allocator = new LikeVersionAllocator(redis, properties, mock(LikeTelemetry.class));
    }
    @AfterEach void clean() { if (allocator != null) { redis.delete(allocator.keys()); } if (factory != null) { factory.destroy(); } }

    @Test void pausedAllocatorCannotIssueOrResetUsedEpoch() {
        assertThrows(ApiException.class, allocator::next);
        String runtime = allocator.recoveryRuntime();
        allocator.initialize(1, runtime); allocator.activate(1, runtime);
        assertTrue(allocator.ready()); assertEquals("0",allocator.status().get(1));
        assertEquals(LikeVersion.pack(1, 1), allocator.next());
        allocator.pause(); assertThrows(ApiException.class, allocator::next);
        assertThrows(RuntimeException.class, () -> allocator.initialize(1, runtime));
        allocator.activate(1, runtime);
        assertEquals(LikeVersion.pack(1, 2), allocator.next());
        allocator.pause(); allocator.initialize(2, runtime); allocator.activate(2, runtime);
        assertTrue(allocator.next() > LikeVersion.pack(1, LikeVersion.SEQUENCE_LIMIT - 1));
        assertThrows(RuntimeException.class, () -> allocator.initialize(1, runtime));
    }

    @Test void oldRuntimeKeepsIssuingWithoutChangingGuardOrConsumingStatusSequence() {
        initialize(1);
        assertEquals(LikeVersion.pack(1, 1), allocator.next());
        // 模拟正常重启后的旧维护标识；无需真正重启容器即可验证放行条件。
        redis.opsForHash().put(allocator.keys().get(2), "runId", "previous-process");

        assertTrue(allocator.ready());
        assertEquals(Status.UP, new LikeAllocatorHealth(allocator).health().getStatus());
        var status = allocator.status();
        assertEquals("1", status.get(0));
        assertEquals("1", status.get(1));
        assertEquals("previous-process", status.get(3));
        assertNotEquals(status.get(3), status.get(4));
        assertEquals(LikeVersion.pack(1, 2), allocator.next());
        assertEquals("previous-process", allocator.status().get(3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missingEpoch", "missingSequence", "missingGuard", "missingGuardEpoch", "missingGuardRun", "missingState",
            "emptyGuardRun", "wrongEpochType", "wrongSequenceType", "wrongGuardType", "mismatchedEpoch",
            "invalidEpoch", "invalidSequence", "exhaustedSequence", "paused"
    })
    void invalidAllocatorStillRefuses(String fault) {
        initialize(1);
        var keys = allocator.keys();
        // 每次只破坏一个前提，确保放宽运行标识校验没有同时放宽数据完整性校验。
        switch (fault) {
            case "missingEpoch" -> redis.delete(keys.get(0));
            case "missingSequence" -> redis.delete(keys.get(1));
            case "missingGuard" -> redis.delete(keys.get(2));
            case "missingGuardEpoch" -> redis.opsForHash().delete(keys.get(2), "epoch");
            case "missingGuardRun" -> redis.opsForHash().delete(keys.get(2), "runId");
            case "missingState" -> redis.opsForHash().delete(keys.get(2), "state");
            case "emptyGuardRun" -> redis.opsForHash().put(keys.get(2), "runId", "");
            case "wrongEpochType", "wrongSequenceType" -> {
                String key = keys.get(fault.equals("wrongEpochType") ? 0 : 1);
                redis.delete(key);
                redis.opsForList().rightPush(key, "1");
            }
            case "wrongGuardType" -> {
                redis.delete(keys.get(2));
                redis.opsForValue().set(keys.get(2), "invalid");
            }
            case "mismatchedEpoch" -> redis.opsForHash().put(keys.get(2), "epoch", "2");
            case "invalidEpoch" -> redis.opsForValue().set(keys.get(0), "-1");
            case "invalidSequence" -> redis.opsForValue().set(keys.get(1), "invalid");
            case "exhaustedSequence" -> redis.opsForValue().set(keys.get(1), Long.toString(LikeVersion.SEQUENCE_LIMIT - 1));
            case "paused" -> allocator.pause();
            default -> throw new IllegalArgumentException("未知测试故障：" + fault);
        }
        assertFalse(allocator.ready());
        assertThrows(ApiException.class, allocator::next);
        if (fault.equals("exhaustedSequence")) {
            assertEquals(Long.toString(LikeVersion.SEQUENCE_LIMIT - 1), redis.opsForValue().get(keys.get(1)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void everyAllocatorKeyMustRemainPersistent(int keyIndex) {
        initialize(1);
        redis.expire(allocator.keys().get(keyIndex), Duration.ofHours(1));
        assertFalse(allocator.ready());
        assertThrows(ApiException.class, allocator::next);
    }

    @Test void runtimeChangeCannotBypassPauseOrMaintenanceRuntimeChecks() {
        String runtime = initialize(1);
        redis.opsForHash().put(allocator.keys().get(2), "runId", "previous-process");
        assertThrows(RuntimeException.class, () -> allocator.initialize(2, runtime));
        assertEquals("1", allocator.status().get(0));
        assertEquals("READY", allocator.status().get(2));

        allocator.pause();
        assertThrows(RuntimeException.class, () -> allocator.initialize(2, "previous-process"));
        // 旧guard不能用当前进程标识激活，也不能用旧标识绕过当前进程校验。
        assertThrows(RuntimeException.class, () -> allocator.activate(1, runtime));
        assertThrows(RuntimeException.class, () -> allocator.activate(1, "previous-process"));
        assertEquals("PAUSED", allocator.status().get(2));

        allocator.initialize(2, runtime);
        assertThrows(RuntimeException.class, () -> allocator.activate(1, runtime));
        assertThrows(RuntimeException.class, () -> allocator.activate(2, "previous-process"));
        allocator.activate(2, runtime);
        // 迟到的失败清理不能暂停已激活的新纪元。
        assertThrows(RuntimeException.class, () -> allocator.pauseIf(1, "previous-process"));
        assertTrue(allocator.ready());
    }

    @Test void higherEpochProtectsVersionsAfterPossibleSequenceRollback() {
        String runtime = initialize(1);
        redis.opsForValue().set(allocator.keys().get(1), "100");
        long old = allocator.next();
        redis.opsForValue().set(allocator.keys().get(1), "0");
        redis.opsForHash().put(allocator.keys().get(2), "runId", "previous-process");
        // CHECK不检测历史回退；运维必须在恢复业务前暂停并提升纪元。
        assertTrue(allocator.ready());
        allocator.pause();
        allocator.initialize(2, runtime);
        allocator.activate(2, runtime);
        long recovered = allocator.next();
        assertEquals(LikeVersion.pack(2, 1), recovered);
        assertTrue(recovered > old);
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

    /** 模拟现有恢复命令已提交SQL新纪元后的Redis初始化及激活步骤。 */
    private String initialize(int epoch) {
        String runtime = allocator.recoveryRuntime();
        allocator.initialize(epoch, runtime);
        allocator.activate(epoch, runtime);
        return runtime;
    }
}
