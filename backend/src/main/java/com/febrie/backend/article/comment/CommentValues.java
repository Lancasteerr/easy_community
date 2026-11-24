package com.febrie.backend.article.comment;

import com.febrie.backend.shared.cache.CacheProtocol;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 评论对象与文章评论数各自使用独立的键和短租期操作锁。 */
@Component
public class CommentValues {
    private static final Logger log = LoggerFactory.getLogger(CommentValues.class);
    private static final String MISSING = "-";
    private static final RedisScript<Long> MUTATE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if ARGV[2] == 'write' then
                redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[4])
            elseif ARGV[2] == 'delete' then
                redis.call('DEL', KEYS[2])
            end
            redis.call('DEL', KEYS[1])
            return 1
            """, Long.class);

    private final CommentStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String namespace;

    public CommentValues(CommentStore store, StringRedisTemplate redis, ObjectMapper json,
                         @Value("${app.auth.redis-prefix}") String prefix) {
        this.store = store;
        this.redis = redis;
        this.json = json;
        this.namespace = prefix + ":comment:v1:";
    }

    public Map<Long, Long> counts(Collection<Long> ids) {
        var counts = load("article-count", ids, 10_000, store::counts, Object::toString, Long::valueOf);
        if (!counts.keySet().containsAll(ids)) throw CommentErrors.unavailable();
        return counts;
    }

    Map<Long, CommentRow> objects(Collection<Long> ids) {
        return load("visible-object", ids, 120_000, store::findAll, json::writeValueAsString,
                value -> json.readValue(value, CommentRow.class));
    }

    void invalidateCount(long articleId) {
        if (!write("article-count", articleId, "delete", 0, "")) {
            log.warn("文章评论计数缓存竞争 articleId={}", articleId);
        }
    }

    boolean invalidateObject(long id) {
        return write("visible-object", id, "delete", 0, "");
    }

    private boolean write(String kind, long id, String operation, long ttl, String value) {
        String key = key(kind, id);
        long deadline = System.nanoTime() + CacheProtocol.WAIT_BUDGET.toNanos();
        do {
            String token = tryLock(key);
            if (token != null) return mutate(key, token, operation, ttl, value);
        } while (CacheProtocol.awaitRetry(deadline));
        return false;
    }

    private <T> Map<Long, T> load(String kind, Collection<Long> ids, long ttl,
                                  Function<Collection<Long>, Map<Long, T>> query,
                                  Function<T, String> encode, Function<String, T> decode) {
        var pending = new LinkedHashSet<>(ids);
        var results = new HashMap<Long, T>();
        long deadline = System.nanoTime() + CacheProtocol.WAIT_BUDGET.toNanos();
        while (!pending.isEmpty()) {
            read(kind, pending, pending, results, decode);
            if (pending.isEmpty()) break;
            var held = new LinkedHashMap<Long, String>();
            try {
                for (long id : pending) {
                    String token = tryLock(key(kind, id));
                    if (token != null) held.put(id, token);
                }
                if (!held.isEmpty()) {
                    // 拿锁后重新批量确认，只有缺失项进入 SQL。
                    var missing = new LinkedHashSet<>(held.keySet());
                    read(kind, held.keySet(), missing, results, decode);
                    for (long id : List.copyOf(held.keySet())) {
                        if (!missing.contains(id)) {
                            pending.remove(id);
                            release(key(kind, id), held.remove(id));
                        }
                    }
                    if (!missing.isEmpty()) {
                        var loaded = query.apply(missing);
                        for (long id : missing) {
                            T value = loaded.get(id);
                            if (mutate(key(kind, id), held.get(id), "write", value == null ? 2_000 : ttl,
                                    value == null ? MISSING : encode.apply(value))) {
                                if (value != null) results.put(id, value);
                                pending.remove(id);
                            }
                            held.remove(id);
                        }
                    }
                }
            } finally {
                held.forEach((id, token) -> release(key(kind, id), token));
            }
            if (!pending.isEmpty() && !CacheProtocol.awaitRetry(deadline)) throw CommentErrors.unavailable();
        }
        return results;
    }

    private <T> void read(String kind, Collection<Long> ids, Collection<Long> missing, Map<Long, T> results,
                          Function<String, T> decode) {
        var ordered = new ArrayList<>(ids);
        var values = redis.opsForValue().multiGet(ordered.stream().map(id -> key(kind, id)).toList());
        if (values == null || values.size() != ordered.size()) throw CommentErrors.unavailable();
        for (int i = 0; i < ordered.size(); i++) {
            var value = values.get(i);
            if (value == null) continue;
            long id = ordered.get(i);
            if (!MISSING.equals(value)) results.put(id, decode.apply(value));
            missing.remove(id);
        }
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key + ":lock", token, CacheProtocol.LOCK_TTL))
                ? token : null;
    }

    private boolean mutate(String key, String token, String operation, long ttl, String value) {
        return Long.valueOf(1).equals(redis.execute(MUTATE, List.of(key + ":lock", key), token,
                operation, Long.toString(ttl), value));
    }

    private void release(String key, String token) {
        try {
            mutate(key, token, "unlock", 0, "");
        } catch (RuntimeException failure) {
            log.warn("评论缓存锁释放失败 error={}", failure.getClass().getSimpleName());
        }
    }

    private String key(String kind, long id) {
        return namespace + kind + ":{" + id + "}";
    }
}
