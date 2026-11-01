package com.febrie.backend.auth.infrastructure;

import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import com.febrie.backend.auth.infrastructure.ratelimit.RequestLimits;
import com.febrie.backend.auth.infrastructure.session.ActiveSessions;
import com.febrie.backend.shared.ratelimit.FixedWindowCounter;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.session.MapSessionRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 脚本无成功标记时必须按依赖失败处理，不以空结果或零值继续认证。 */
class RedisWriteFailureTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Clock clock = Clock.systemUTC();

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, 2})
    void failureRecordingRequiresExplicitSuccess(Long result) {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyString())).thenReturn(result);
        var limits = new RequestLimits(redis, properties(Duration.ofHours(2)), clock, new FixedWindowCounter(redis));
        assertThrows(RedisSystemException.class, () -> limits.failed("Alice_1", "192.0.2.1"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, 2})
    void sessionRegistrationRequiresExplicitSuccess(Long result) {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), anyList(), anyString(), anyString()))
                .thenReturn(result);
        var active = new ActiveSessions(redis, properties(Duration.ofHours(2)),
                new MapSessionRepository(new ConcurrentHashMap<>()), clock);
        var now = Instant.now();
        var principal = new SessionPrincipal(1, "Alice_1", Role.USER, 1, now, now.plusSeconds(7200));
        assertThrows(RedisSystemException.class, () -> active.register(principal, "session-id"));
    }

    @Test
    void invalidSessionTtlIsRejectedBeforeRegistration() {
        for (Duration ttl : new Duration[]{Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> new ActiveSessions(redis, properties(ttl),
                    new MapSessionRepository(new ConcurrentHashMap<>()), clock));
        }
        assertThrows(ArithmeticException.class, () -> new ActiveSessions(redis,
                properties(Duration.ofSeconds(Long.MAX_VALUE)), new MapSessionRepository(new ConcurrentHashMap<>()), clock));
        verifyNoInteractions(redis);
    }

    private AuthProperties properties(Duration sessionTtl) {
        return new AuthProperties("test", "ec:test", false, sessionTtl,
                60, 30, 120, 5, 30, Duration.ofMinutes(10));
    }
}
