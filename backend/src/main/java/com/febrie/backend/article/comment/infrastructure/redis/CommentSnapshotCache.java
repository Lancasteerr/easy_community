package com.febrie.backend.article.comment.infrastructure.redis;

import com.febrie.backend.article.comment.CommentErrors;
import com.febrie.backend.article.comment.CommentListKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
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
            local lower = ARGV[3] == '' and '-' or '(' .. ARGV[3]
            local rows = redis.call('ZRANGEBYLEX', KEYS[2], lower, '+', 'LIMIT', 0, ARGV[4])
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

    public CommentSnapshot current(CommentListKey key) {
        return CommentSnapshot.parse(redis.opsForValue().get(base(key) + ":current"));
    }

    public Map<CommentListKey, CommentSnapshot> currentAll(List<CommentListKey> keys) {
        var snapshots = new LinkedHashMap<CommentListKey, CommentSnapshot>();
        if (keys.isEmpty()) return snapshots;
        var raw = redis.opsForValue().multiGet(keys.stream().map(key -> base(key) + ":current").toList());
        if (raw == null || raw.size() != keys.size()) throw CommentErrors.unavailable();
        for (int i = 0; i < keys.size(); i++) {
            var snapshot = CommentSnapshot.parse(raw.get(i));
            if (snapshot != null) snapshots.put(keys.get(i), snapshot);
        }
        return snapshots;
    }

    public Map<CommentListKey, List<String>> firstPages(Map<CommentListKey, CommentSnapshot> snapshots, int size) {
        var out = new LinkedHashMap<CommentListKey, List<String>>();
        if (snapshots.isEmpty()) return out;
        // 每个脚本仅访问自己楼层的哈希槽，合并网络往返。
        var replies = redis.executePipelined(new SessionCallback<Object>() {
            @Override public <K, V> Object execute(RedisOperations<K, V> operations) {
                snapshots.forEach((key, snapshot) -> readPage(key, snapshot, "", size));
                return null;
            }
        });
        if (replies.size() != snapshots.size()) throw CommentErrors.unavailable();
        int index = 0;
        for (var key : snapshots.keySet()) {
            var result = pageRows((List<?>) replies.get(index++));
            if (result != null) out.put(key, result);
        }
        return out;
    }

    public CommentSnapshot snapshot(CommentListKey key, String id) {
        return CommentSnapshot.parse(redis.opsForValue().get(meta(key, id)));
    }

    public boolean available(CommentListKey key, CommentSnapshot snapshot) {
        return snapshot != null && snapshot(key, snapshot.id()) != null
                && (snapshot.count() == 0 || Boolean.TRUE.equals(redis.hasKey(rows(key, snapshot.id()))));
    }

    public String lock(CommentListKey key) {
        return claim(base(key) + ":build", Duration.ofSeconds(15));
    }

    public void release(CommentListKey key, String token) {
        redis.execute(RELEASE, List.of(base(key) + ":build"), token);
    }

    public String claimRefresh(CommentListKey key, String snapshotId) {
        return claim(base(key) + ":refresh:" + snapshotId, Duration.ofSeconds(5));
    }

    private String claim(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl)) ? token : null;
    }

    public void stage(CommentListKey key, String id, String token, List<String> batch) {
        List<String> args = new ArrayList<>(batch.size() + 1);
        args.add(token);
        args.addAll(batch);
        if (!Long.valueOf(1).equals(redis.execute(STAGE, List.of(base(key) + ":build", rows(key, id)),
                args.toArray()))) throw CommentErrors.unavailable();
    }

    public CommentSnapshot publish(CommentListKey key, CommentSnapshot expected, String id, String token,
                            long count, long now, boolean refresh) {
        long soft = count == 0 ? 2_000 : 5_000;
        long ttl = count == 0 ? 10_000 : 60_000;
        String previous = expected == null ? "none" : expected.id();
        String payload = redis.execute(PUBLISH, List.of(base(key) + ":build", base(key) + ":current",
                        meta(key, id), rows(key, id),
                        meta(key, previous), rows(key, previous)),
                token, expected == null ? "" : expected.payload(), id, Long.toString(count),
                refresh ? "1" : "0", Long.toString(now + soft),
                Long.toString(now + ttl), Long.toString(ttl));
        return CommentSnapshot.parse(payload);
    }

    public List<String> page(CommentListKey key, CommentSnapshot snapshot, String position, int size) {
        return pageRows(readPage(key, snapshot, position, size));
    }

    private List<?> readPage(CommentListKey key, CommentSnapshot snapshot, String position, int size) {
        return redis.execute(PAGE, List.of(meta(key, snapshot.id()), rows(key, snapshot.id())),
                snapshot.payload(), Long.toString(snapshot.count()), position, Integer.toString(size));
    }

    private static List<String> pageRows(List<?> raw) {
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
