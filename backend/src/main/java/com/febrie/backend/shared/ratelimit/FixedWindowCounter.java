package com.febrie.backend.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

/** 通用窗口计数；Lua 原子递增并刷新 TTL，窗口与限额仍由调用方决定。 */
@Component
public class FixedWindowCounter {
    // 单例脚本复用 SHA；KEYS[1] 为计数 Key，ARGV[1] 为正整数毫秒 TTL。
    private static final RedisScript<String> INCREMENT = RedisScript.of(
            new ClassPathResource("redis/increment-window.lua"), String.class);

    private final StringRedisTemplate redis;
    public FixedWindowCounter(StringRedisTemplate redis) { this.redis = redis; }

    public long increment(String key, Duration ttl) {
        Assert.notNull(ttl, "窗口过期时间不能为空");
        // toMillis 同时检查 long 溢出；不足一毫秒的 TTL 不可用于计数。
        long ttlMillis = ttl.toMillis();
        Assert.isTrue(ttlMillis > 0, "窗口过期时间必须至少为一毫秒");
        String count = redis.execute(INCREMENT, List.of(key), Long.toString(ttlMillis));
        if (count == null) {
            throw new RedisSystemException("窗口计数未返回结果", null);
        }
        try {
            return Long.parseLong(count);
        } catch (NumberFormatException error) {
            throw new RedisSystemException("窗口计数返回值无效", error);
        }
    }
}
