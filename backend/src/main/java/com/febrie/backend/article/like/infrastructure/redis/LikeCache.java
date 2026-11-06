package com.febrie.backend.article.like.infrastructure.redis;

import com.febrie.backend.article.like.application.LikeProjection;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;

import com.febrie.backend.article.like.domain.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** 展示缓存与签发器分开命名；缓存可以丢失，但只能用固定TTL最终修复。 */
@Component
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeCache implements LikeProjection {
    private static final RedisScript<List> STATE = RedisScript.of(new ClassPathResource("redis/like-state.lua"), List.class);
    private static final RedisScript<Long> COUNTS = RedisScript.of(new ClassPathResource("redis/like-count-fill.lua"), Long.class);
    private final StringRedisTemplate redis;
    private final LikeProperties properties;
    public LikeCache(StringRedisTemplate redis, LikeProperties properties) { this.redis=redis; this.properties=properties; }
    public String stateKey(long user) { return properties.key("cache:v1:state:{" + user + "}"); }
    public String countKey(long article) { return properties.key("cache:v1:count:{" + article + "}"); }

    public Map<Long, Long> counts(Collection<Long> ids) {
        var ordered = List.copyOf(ids);
        Map<Long, Long> result = new LinkedHashMap<>();
        if (ordered.isEmpty()) { return result; }
        var values = Objects.requireNonNull(redis.opsForValue().multiGet(ordered.stream().map(this::countKey).toList()));
        for (int i=0;i<ordered.size();i++) {
            String value=values.get(i);
            if (value != null) {
                long count = Long.parseLong(value);
                if (count < 0) { throw new IllegalStateException("缓存计数为负数"); }
                result.put(ordered.get(i), count);
            }
        }
        return result;
    }

    public Map<Long, LikeState> states(long user, Collection<Long> ids) {
        var ordered = List.copyOf(ids);
        Map<Long, LikeState> result = new LinkedHashMap<>();
        if (ordered.isEmpty()) { return result; }
        var fields = ordered.stream().map(id -> (Object) Long.toString(id)).toList();
        var values = redis.opsForHash().multiGet(stateKey(user), fields);
        for (int i=0;i<ordered.size();i++) {
            if (values.get(i) != null) { result.put(ordered.get(i), decode(user, ordered.get(i), values.get(i).toString())); }
        }
        return result;
    }

    public Map<Long, LikeState> putStates(long user, Collection<LikeState> states, String phase) {
        var ordered = List.copyOf(states);
        if (ordered.isEmpty()) { return Map.of(); }
        List<String> args = new ArrayList<>(List.of(phase, "600"));
        for (var state: ordered) {
            if (state.userId() != user) { throw new IllegalArgumentException("不能向其他用户缓存填充状态"); }
            args.add(Long.toString(state.articleId())); args.add(LikeVersion.cache(state.version())); args.add(state.liked()?"1":"0");
        }
        var winners = Objects.requireNonNull(redis.execute(STATE, List.of(stateKey(user)), args.toArray()));
        Map<Long, LikeState> result = new LinkedHashMap<>();
        for (int i=0;i<ordered.size();i++) { long article=ordered.get(i).articleId(); result.put(article, decode(user, article, winners.get(i).toString())); }
        return result;
    }

    public void putCounts(Map<Long, Long> counts) {
        if (counts.isEmpty()) { return; }
        List<String> keys = new ArrayList<>(), args = new ArrayList<>(List.of("30"));
        counts.forEach((id,count) -> { keys.add(countKey(id)); args.add(Long.toString(count)); });
        if (!Long.valueOf(1).equals(redis.execute(COUNTS, keys, args.toArray()))) { throw new IllegalStateException("计数回填未确认"); }
    }

    @Override public void pending(LikeCommand command) {
        putStates(command.userId(), List.of(new LikeState(command.userId(), command.articleId(), command.desiredLiked(), command.number())), "P");
    }
    @Override public void committed(LikeSqlService.Result result) {
        if (result.status() == LikeSqlService.Status.STALE) { return; }
        var state=result.state();
        try { putStates(state.userId(), List.of(state), "C"); }
        finally { redis.delete(countKey(state.articleId())); }
    }

    private static LikeState decode(long user, long article, String raw) {
        if (!raw.matches("[0-9]{19}\\|[01]\\|[PC]")) { throw new IllegalStateException("点赞状态缓存损坏"); }
        return new LikeState(user, article, raw.charAt(20)=='1', Long.parseLong(raw.substring(0,19)));
    }
}
