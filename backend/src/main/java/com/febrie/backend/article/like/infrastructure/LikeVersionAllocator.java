package com.febrie.backend.article.like.infrastructure;

import com.febrie.backend.article.like.application.LikeErrors;
import com.febrie.backend.article.like.domain.LikeVersion;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 故障只停止签发，绝不根据SQL关系最大版本重置序号。 */
@Component
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeVersionAllocator {
    private static final ClassPathResource SCRIPT = new ClassPathResource("redis/like-version.lua");
    private static final RedisScript<List> READ = RedisScript.of(SCRIPT, List.class);
    private static final RedisScript<Long> WRITE = RedisScript.of(SCRIPT, Long.class);
    private final StringRedisTemplate redis;
    private final LikeProperties properties;
    private final LikeTelemetry telemetry;
    public LikeVersionAllocator(StringRedisTemplate redis, LikeProperties properties, LikeTelemetry telemetry) {
        this.redis = redis; this.properties = properties; this.telemetry = telemetry;
    }

    public long next() {
        try {
            var pair = Objects.requireNonNull(redis.execute(READ, keys(), "ALLOCATE"));
            long version = LikeVersion.pack(Long.parseLong(pair.get(0).toString()), Long.parseLong(pair.get(1).toString()));
            telemetry.event("allocator", "issued");
            return version;
        } catch (RuntimeException invalid) { telemetry.event("allocator", "refused"); throw LikeErrors.allocator(); }
    }

    public List<String> keys() { return List.of(properties.key("version:epoch"), properties.key("version:seq"), properties.key("version:guard")); }
    public List<String> status() {
        return Objects.requireNonNull(redis.execute(READ, keys(), "STATUS")).stream().map(Object::toString).toList();
    }
    public String recoveryRuntime() {
        var policy = redis.execute((RedisCallback<Properties>) c -> c.serverCommands().getConfig("maxmemory-policy"));
        if (policy == null || !"noeviction".equals(policy.getProperty("maxmemory-policy"))) {
            throw new IllegalStateException("版本分配器必须使用noeviction策略");
        }
        return status().get(4);
    }
    public void pause() { execute("PAUSE", "", ""); }
    public boolean ready() {
        try { execute("CHECK", "", ""); return true; }
        catch (RuntimeException invalid) { return false; }
    }
    public void pauseIf(int epoch, String runtime) { execute("PAUSE", Integer.toString(epoch), runtime); }
    public void initialize(int epoch, String runtime) { execute("INITIALIZE", Integer.toString(epoch), runtime); }
    public void activate(int epoch, String runtime) { execute("ACTIVATE", Integer.toString(epoch), runtime); }
    private void execute(String action, String epoch, String runtime) {
        Long result = redis.execute(WRITE, keys(), action, epoch, runtime);
        if (result == null || result != 1) { throw new IllegalStateException("版本维护条件不匹配：" + action); }
    }
}
