package com.febrie.backend.user.infrastructure.cache;

import com.febrie.backend.shared.cache.*;
import com.febrie.backend.user.application.dto.UserBasicData;
import com.febrie.backend.user.infrastructure.config.UserBasicCacheProperties;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 单用户一个展示投影；所有变更由同槽Lua验证当前锁token。 */
@Component
public class UserBasicCacheStore {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UserBasicCacheStore.class);
    private static final RedisScript<Long> OPERATE = RedisScript.of(new ClassPathResource("redis/user-basic-cache.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final String namespace;
    private final ObjectMapper mapper;
    private final CacheMetrics metrics;

    public UserBasicCacheStore(StringRedisTemplate redis, UserBasicCacheProperties properties, ObjectMapper mapper, CacheMetrics metrics) {
        this.redis = redis; this.namespace = properties.namespace(); this.mapper = mapper; this.metrics = metrics;
    }
    public String key(long id, String suffix) { return namespace + ":{" + id + "}:" + suffix; }
    public Map<Long, CacheValue<UserBasicData>> get(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        var ordered = new ArrayList<>(ids);
        var values = redis.opsForValue().multiGet(ordered.stream().map(id -> key(id, "data")).toList());
        if (values == null || values.size() != ordered.size()) {
            throw new RedisSystemException("用户批量缓存未返回完整结果", null);
        }
        Map<Long, CacheValue<UserBasicData>> result = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            String value = values.get(i);
            CacheValue<UserBasicData> decoded;
            if (value == null) { decoded = CacheValue.miss(); }
            else if (value.equals("M")) { decoded = CacheValue.negative(); }
            else if (value.startsWith("D")) {
                var data = mapper.readValue(value.substring(1), UserBasicData.class);
                if (data.userId() != ordered.get(i) || data.username() == null) {
                    throw new IllegalStateException("用户缓存数据与键不一致");
                }
                decoded = CacheValue.hit(data);
            } else { throw new IllegalStateException("用户缓存格式无效"); }
            record(switch (decoded.state()) { case HIT -> "hit"; case NEGATIVE -> "negative_hit"; case MISS -> "miss"; });
            result.put(ordered.get(i), decoded);
        }
        return result;
    }
    public String tryLock(long id) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id, "cache-lock"), token, CacheProtocol.LOCK_TTL)) ? token : null;
    }
    public boolean publish(long id, String token, UserBasicData data) {
        return operate(id, token, "PUBLISH", "D" + mapper.writeValueAsString(data), CacheProtocol.dataTtlMillis());
    }
    public boolean missing(long id, String token) { return operate(id, token, "MISSING", "", 20_000); }
    public boolean invalidate(long id, String token) { return operate(id, token, "INVALIDATE", "", 20_000); }
    public boolean unlock(long id, String token) { return operate(id, token, "UNLOCK", "", 20_000); }

    /** 解锁异常不能覆盖业务结果，有限租期负责最终释放。 */
    public void release(long id, String token) {
        try { unlock(id, token); }
        catch (RuntimeException error) { log.warn("用户缓存解锁异常 userId={} error={}", id, error.getClass().getSimpleName()); }
    }
    public void record(String outcome) { metrics.record("user_basic", outcome); }

    private boolean operate(long id, String token, String action, String payload, long ttl) {
        Long result = redis.execute(OPERATE, List.of(key(id, "cache-lock"), key(id, "data")),
                token, action, payload, Long.toString(ttl));
        if (result == null || (result != 0 && result != 1)) { throw new RedisSystemException("用户脚本返回值无效", null); }
        if (result == 0) { record("token_rejected"); }
        return result == 1;
    }
}
