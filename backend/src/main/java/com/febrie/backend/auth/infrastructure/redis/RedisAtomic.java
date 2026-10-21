package com.febrie.backend.auth.infrastructure.redis;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 将写入与TTL放在同一连接的Redis事务中，不使用Lua脚本。 */
@Component
public class RedisAtomic {
    private final StringRedisTemplate redis;
    public RedisAtomic(StringRedisTemplate redis) { this.redis = redis; }

    public List<Object> execute(Consumer<RedisOperations<String, String>> commands) {
        var result = redis.execute(new SessionCallback<List<Object>>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> List<Object> execute(RedisOperations<K, V> operations) {
                var strings = (RedisOperations<String, String>) (RedisOperations<?, ?>) operations;
                operations.multi();
                try {
                    commands.accept(strings);
                } catch (RuntimeException error) {
                    operations.discard();
                    throw error;
                }
                return operations.exec();
            }
        });
        if (result == null || result.stream().anyMatch(Exception.class::isInstance)) {
            throw new RedisSystemException("Redis事务未成功提交", null);
        }
        return result;
    }
}
