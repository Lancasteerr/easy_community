package com.febrie.backend.article.content.infrastructure.cache;

import com.febrie.backend.article.content.infrastructure.config.ArticleCacheProperties;
import java.time.Duration;
import com.febrie.backend.shared.cache.CacheProtocol;
import com.febrie.backend.shared.cache.CacheMetrics;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 两种投影共用一把锁；所有缓存变更均在Lua内验证当前token。 */
@Component
public class ArticleCache {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ArticleCache.class);
    public static final Duration LOCK_TTL = CacheProtocol.LOCK_TTL;
    public static final Duration WAIT_BUDGET = CacheProtocol.WAIT_BUDGET;
    private static final RedisScript<Long> OPERATE = RedisScript.of(new ClassPathResource("redis/article-cache.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final String namespace;
    private final CacheMetrics metrics;
    public ArticleCache(StringRedisTemplate redis, ArticleCacheProperties properties, CacheMetrics metrics) {
        this.redis = redis; this.namespace = properties.namespace(); this.metrics = metrics;
    }
    public String key(long id, String suffix) { return namespace + ":{" + id + "}:" + suffix; }

    /** 仅离线恢复命令使用，旧命名空间或已有写入的版本都不能原地重建。 */
    public void requireUnusedNamespace() {
        try (var keys = redis.scan(ScanOptions.scanOptions().match(namespace + ":*").count(1).build())) {
            if (keys.hasNext()) { throw new IllegalStateException("恢复必须选择未使用的文章缓存版本"); }
        }
    }
    public void markRebuilt() { redis.opsForValue().set(namespace + ":rebuild-complete", "1"); }

    public String tryLock(long id) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id, "cache-lock"), token, LOCK_TTL)) ? token : null;
    }
    public boolean hidden(long id) { return Boolean.TRUE.equals(redis.hasKey(key(id, "hidden"))); }
    public Map<Long, Boolean> hidden(Collection<Long> ids) {
        List<Long> ordered = new ArrayList<>(ids);
        List<String> values = values(ordered.stream().map(id -> key(id, "hidden")).toList());
        Map<Long, Boolean> result = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) { result.put(ordered.get(i), values.get(i) != null); }
        return result;
    }
    public String value(long id, String projection) { return redis.opsForValue().get(key(id, projection)); }
    public Map<Long, String> values(Collection<Long> ids, String projection) {
        List<Long> ordered = new ArrayList<>(ids);
        List<String> values = values(ordered.stream().map(id -> key(id, projection)).toList());
        Map<Long, String> result = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) { result.put(ordered.get(i), values.get(i)); }
        return result;
    }
    private List<String> values(List<String> keys) {
        if (keys.isEmpty()) { return List.of(); }
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null || values.size() != keys.size()) { throw new RedisSystemException("文章批量缓存未返回完整结果", null); }
        return values;
    }

    public boolean publish(long id, String token, String projection, String payload) {
        if (!(projection.equals("detail") || projection.equals("list"))) { throw new IllegalArgumentException("无效文章投影"); }
        long ttl = CacheProtocol.dataTtlMillis();
        return operate(id, token, projection.equals("detail") ? "PUBLISH_DETAIL" : "PUBLISH_LIST", payload, ttl);
    }
    public boolean missing(long id, String token) { return operate(id, token, "MISSING", "", 20_000); }
    public boolean invalidate(long id, String token) { return operate(id, token, "INVALIDATE", "", 20_000); }
    public boolean hide(long id, String token) { return operate(id, token, "HIDE", "", 20_000); }
    public boolean unlock(long id, String token) { return operate(id, token, "UNLOCK", "", 20_000); }
    /** 释放失败只能等待有限租期，不能覆盖已取得的SQL/业务结果。 */
    public void release(long id, String token) {
        try { if (!unlock(id, token)) { log.debug("文章锁已失效 articleId={}", id); } }
        catch (RuntimeException error) { log.warn("文章缓存解锁异常 articleId={} error={}", id, error.getClass().getSimpleName()); }
    }
    public void record(String outcome) { metrics.record("article", outcome); }

    private boolean operate(long id, String token, String action, String payload, long ttl) {
        Long result = redis.execute(OPERATE, List.of(key(id, "cache-lock"), key(id, "hidden"), key(id, "detail"), key(id, "list")),
                token, action, payload, Long.toString(ttl));
        if (result == null || (result != 0 && result != 1)) { throw new RedisSystemException("文章脚本返回值无效", null); }
        if (result == 0) { record("token_rejected"); }
        return result == 1;
    }
}
