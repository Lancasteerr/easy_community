package com.febrie.backend.article.comment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 列表成员按定宽元组存入 ZSet；临时键、指针、版本和锁共用列表哈希槽。 */
@Component
public class CommentSnapshotCache {
    private static final RedisScript<String> PUBLISH = RedisScript.of(
            new ClassPathResource("redis/comment-snapshot-publish.lua"), String.class);
    private static final RedisScript<Long> STAGE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            for i = 2, #ARGV do redis.call('ZADD', KEYS[2], 0, ARGV[i]) end
            redis.call('PEXPIRE', KEYS[2], 90000)
            redis.call('PEXPIRE', KEYS[1], 15000)
            return 1
            """, Long.class);
    private static final RedisScript<List> PAGE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return {'missing'} end
            if ARGV[2] == '0' then return {'ok'} end
            if redis.call('EXISTS', KEYS[2]) == 0 then return {'missing'} end
            local rows
            if ARGV[3] == 'HOT' then
                local first = tonumber(ARGV[4])
                rows = redis.call('ZREVRANGE', KEYS[2], first, first + tonumber(ARGV[5]) - 1)
            elseif ARGV[3] == 'LATEST' then
                local upper = ARGV[4] == '' and '+' or '(' .. ARGV[4]
                rows = redis.call('ZREVRANGEBYLEX', KEYS[2], upper, '-', 'LIMIT', 0, ARGV[5])
            else
                local lower = ARGV[4] == '' and '-' or '(' .. ARGV[4]
                rows = redis.call('ZRANGEBYLEX', KEYS[2], lower, '+', 'LIMIT', 0, ARGV[5])
            end
            table.insert(rows, 1, 'ok')
            return rows
            """, List.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public CommentSnapshotCache(StringRedisTemplate redis, @Value("${app.auth.redis-prefix}") String prefix) {
        this.redis = redis;
        this.prefix = prefix + ":comment:v1:list:";
    }

    CommentSnapshot current(CommentListKey key) {
        return CommentSnapshot.parse(redis.opsForValue().get(base(key) + ":current"));
    }

    CommentSnapshot snapshot(CommentListKey key, String id) {
        return CommentSnapshot.parse(redis.opsForValue().get(meta(key, id)));
    }

    boolean available(CommentListKey key, CommentSnapshot snapshot) {
        return snapshot != null && snapshot(key, snapshot.id()) != null
                && (snapshot.count() == 0 || Boolean.TRUE.equals(redis.hasKey(rows(key, snapshot.id()))));
    }

    String lock(CommentListKey key) {
        return claim(base(key) + ":build", Duration.ofSeconds(15));
    }

    void release(CommentListKey key, String token) {
        redis.execute(RELEASE, List.of(base(key) + ":build"), token);
    }

    String claimRefresh(CommentListKey key, String snapshotId) {
        return claim(base(key) + ":refresh:" + snapshotId, Duration.ofSeconds(5));
    }

    private String claim(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl)) ? token : null;
    }

    void stage(CommentListKey key, String id, String token, List<String> batch) {
        List<String> args = new ArrayList<>(batch.size() + 1);
        args.add(token);
        args.addAll(batch);
        if (!Long.valueOf(1).equals(redis.execute(STAGE, List.of(base(key) + ":build", rows(key, id)),
                args.toArray()))) throw CommentErrors.unavailable();
    }

    CommentSnapshot publish(CommentListKey key, CommentSnapshot expected, String id, String token,
                            long count, long now, boolean refresh) {
        boolean hot = key.sort() == CommentListKey.Sort.HOT;
        long soft = hot ? 60_000 : count == 0 ? 2_000 : 5_000;
        long ttl = hot ? 300_000 : count == 0 ? 10_000 : 60_000;
        String previous = expected == null ? "none" : expected.id();
        String payload = redis.execute(PUBLISH, List.of(base(key) + ":build", base(key) + ":current",
                        meta(key, id), rows(key, id), base(key) + ":rank",
                        meta(key, previous), rows(key, previous)),
                token, expected == null ? "" : expected.payload(), id, Long.toString(count),
                hot ? "1" : "0", refresh ? "1" : "0", Long.toString(now + soft),
                Long.toString(now + ttl), Long.toString(ttl));
        return CommentSnapshot.parse(payload);
    }

    List<String> page(CommentListKey key, CommentSnapshot snapshot, String position, int size) {
        List<?> raw = redis.execute(PAGE, List.of(meta(key, snapshot.id()), rows(key, snapshot.id())),
                snapshot.payload(), Long.toString(snapshot.count()), key.sort().name(), position, Integer.toString(size));
        if (raw == null || raw.isEmpty()) throw CommentErrors.unavailable();
        if ("missing".equals(raw.getFirst())) return null;
        return raw.subList(1, raw.size()).stream().map(String.class::cast).toList();
    }

    private String base(CommentListKey key) {
        return prefix + "{" + key.articleId() + ":" + key.rootId() + ":" + key.sort().name() + "}";
    }

    private String meta(CommentListKey key, String id) {
        return base(key) + ":meta:" + id;
    }

    private String rows(CommentListKey key, String id) {
        return base(key) + ":rows:" + id;
    }
}
