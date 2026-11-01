package com.febrie.backend.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 只验证调用边界和失败分类，计数及过期语义由真实 Redis 测试覆盖。 */
class FixedWindowCounterTests {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final FixedWindowCounter counter = new FixedWindowCounter(redis);

    @Test
    void invalidTtlIsRejectedBeforeWriting() {
        for (Duration ttl : new Duration[]{null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> counter.increment("counter", ttl));
        }
        assertThrows(ArithmeticException.class,
                () -> counter.increment("counter", Duration.ofSeconds(Long.MAX_VALUE)));
        verifyNoInteractions(redis);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"invalid", "9223372036854775808"})
    void missingOrMalformedCountIsADependencyFailure(String result) {
        when(redis.execute(ArgumentMatchers.<RedisScript<String>>any(), eq(List.of("counter")), eq("60000")))
                .thenReturn(result);
        assertThrows(RedisSystemException.class, () -> counter.increment("counter", Duration.ofMinutes(1)));
    }
}
