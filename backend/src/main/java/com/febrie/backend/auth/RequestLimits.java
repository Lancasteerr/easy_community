package com.febrie.backend.auth;

import com.febrie.backend.common.ApiException;
import com.febrie.backend.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 固定窗口入口限制与认证失败计数；计数和过期时间使用Redis事务同步提交。 */
@Component
public class RequestLimits {
    private final StringRedisTemplate redis;
    private final RedisAtomic atomic;
    private final AuthProperties properties;
    private final Clock clock;

    public RequestLimits(StringRedisTemplate redis, RedisAtomic atomic, AuthProperties properties, Clock clock) {
        this.redis = redis;
        this.atomic = atomic;
        this.properties = properties;
        this.clock = clock;
    }

    public void consume(String rule, String subject, int maximum) {
        long window = clock.instant().getEpochSecond() / 60;
        String key = properties.redisPrefix() + ":auth:request:" + rule + ":" + digest(subject) + ":" + window;
        long remaining = 60 - clock.instant().getEpochSecond() % 60;
        var result = atomic.execute(operations -> {
            operations.opsForValue().increment(key);
            operations.expire(key, Duration.ofSeconds(remaining + 60));
        });
        if (((Number) result.getFirst()).longValue() > maximum) { throw exceeded(remaining); }
    }

    public void checkFailures(String username, String ip) {
        long window = clock.instant().getEpochSecond() / 900;
        if (count(failureKey("account", username, window)) >= properties.accountFailures()
                || count(failureKey("ip", ip, window)) >= properties.ipFailures()) {
            throw exceeded(900 - clock.instant().getEpochSecond() % 900);
        }
    }

    public void failed(String username, String ip) {
        long window = clock.instant().getEpochSecond() / 900;
        // 两个维度统一计数；已有并发认证允许完成，不尝试撤回进行中的请求。
        atomic.execute(operations -> {
            for (String key : new String[]{failureKey("account", username, window), failureKey("ip", ip, window)}) {
                operations.opsForValue().increment(key);
                operations.expire(key, Duration.ofMinutes(16));
            }
        });
    }

    private String failureKey(String kind, String value, long window) {
        return properties.redisPrefix() + ":auth:login-fail:" + kind + ":" + digest(value) + ":" + window;
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private static ApiException exceeded(long remaining) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "请求过于频繁，请稍后重试", Math.max(1, remaining));
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前Java运行时缺少SHA-256", impossible);
        }
    }
}
