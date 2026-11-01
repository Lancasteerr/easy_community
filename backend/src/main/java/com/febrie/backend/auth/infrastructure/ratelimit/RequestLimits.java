package com.febrie.backend.auth.infrastructure.ratelimit;

import com.febrie.backend.auth.infrastructure.config.AuthProperties;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.shared.ratelimit.FixedWindowCounter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 固定窗口入口限制与认证失败计数；Lua 原子更新计数及过期时间。 */
@Component
public class RequestLimits {
    // KEYS 按账号、IP 排列；固定的 16 分钟 TTL 转为毫秒，无需每次校验。
    private static final RedisScript<Long> RECORD_FAILURE = RedisScript.of(
            new ClassPathResource("redis/record-login-failure.lua"), Long.class);
    private static final String FAILURE_TTL_MILLIS = Long.toString(Duration.ofMinutes(16).toMillis());

    private final StringRedisTemplate redis;
    private final AuthProperties properties;
    private final Clock clock;
    private final FixedWindowCounter windows;

    public RequestLimits(StringRedisTemplate redis, AuthProperties properties, Clock clock, FixedWindowCounter windows) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.windows = windows;
    }

    public void consume(String rule, String subject, int maximum) {
        long window = clock.instant().getEpochSecond() / 60;
        String key = properties.redisPrefix() + ":auth:request:" + rule + ":" + digest(subject) + ":" + window;
        long remaining = 60 - clock.instant().getEpochSecond() % 60;
        if (windows.increment(key, Duration.ofSeconds(remaining + 60)) > maximum) { throw exceeded(remaining); }
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
        var keys = List.of(failureKey("account", username, window), failureKey("ip", ip, window));
        Long result = redis.execute(RECORD_FAILURE, keys, FAILURE_TTL_MILLIS);
        if (!Long.valueOf(1).equals(result)) {
            throw new RedisSystemException("认证失败计数未成功写入", null);
        }
    }

    private String failureKey(String kind, String value, long window) {
        return properties.redisPrefix() + ":auth:login-fail:" + kind + ":" + digest(value) + ":" + window;
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException error) {
            // 存量计数损坏属于 Redis 依赖故障，不泄露原值，也不放行认证。
            throw new RedisSystemException("认证失败计数值无效", error);
        }
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
