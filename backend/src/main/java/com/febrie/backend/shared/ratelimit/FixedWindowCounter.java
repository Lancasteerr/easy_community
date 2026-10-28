package com.febrie.backend.shared.ratelimit;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;

/** 通用窗口计数；INCR与TTL同事务提交，不依赖文件锁或Lua。 */
@Component
public class FixedWindowCounter {
    private final StringRedisTemplate redis;
    public FixedWindowCounter(StringRedisTemplate redis) { this.redis = redis; }

    public long increment(String key, Duration ttl) {
        List<Object> result = redis.execute(new SessionCallback<>() {
            @Override public <K,V> List<Object> execute(RedisOperations<K,V> operations) {
                @SuppressWarnings("unchecked")
                var strings = (RedisOperations<String,String>) (RedisOperations<?,?>) operations;
                operations.multi();
                try {
                    strings.opsForValue().increment(key);
                    strings.expire(key, ttl);
                } catch (RuntimeException error) { operations.discard(); throw error; }
                return operations.exec();
            }
        });
        if (result == null || result.size() != 2 || !(result.getFirst() instanceof Number count)
                || !Boolean.TRUE.equals(result.get(1))) {
            throw new RedisSystemException("窗口计数未成功提交", null);
        }
        return count.longValue();
    }
}
