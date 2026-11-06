package com.febrie.backend.article.view.infrastructure.cache;

import com.febrie.backend.article.view.application.ArticleViewSqlService.FlushResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

/** 独立统计命名空间；所有Redis访问拒绝在SQL事务内执行。 */
@Component
@Transactional(propagation = Propagation.NEVER)
public class ArticleViewStore {
    private static final Logger log = LoggerFactory.getLogger(ArticleViewStore.class);
    private static final RedisScript<String> OPERATE = RedisScript.of(new ClassPathResource("redis/article-views.lua"), String.class);
    private final StringRedisTemplate redis;
    private final String namespace;

    public ArticleViewStore(StringRedisTemplate redis, @Value("${app.auth.redis-prefix}") String prefix) {
        this.redis = redis;
        namespace = "{" + prefix + ":article:stats:v1}";
    }

    public String dailyKey(LocalDate date) { return namespace + ":pv:" + date.format(DateTimeFormatter.BASIC_ISO_DATE); }
    public String totalKey() { return namespace + ":total"; }

    /** null只表示脚本确认未写入且需要SQL基线；异常不能当作可重试结果。 */
    public Long increment(long articleId, LocalDate date, Long baseline) {
        String value = redis.execute(OPERATE, List.of(dailyKey(date), totalKey()),
                "COUNT", Long.toString(articleId), baseline == null ? "" : baseline.toString());
        if ("NEEDS_BASELINE".equals(value)) { return null; }
        return count(value);
    }

    public Map<Long, Long> totals(Collection<Long> ids) {
        if (ids.isEmpty()) { return Map.of(); }
        List<Long> ordered = new ArrayList<>(ids);
        List<Object> fields = ordered.stream().map(id -> (Object) id.toString()).toList();
        List<Object> values = redis.opsForHash().multiGet(totalKey(), fields);
        if (values.size() != ordered.size()) { throw new IllegalStateException("实时总量未返回完整结果"); }
        Map<Long, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (values.get(i) != null) { result.put(ordered.get(i), count(values.get(i).toString())); }
        }
        return result;
    }

    /** 同一Pipeline批量初始化缺失字段，返回并发初始化或增长后的实际总量。 */
    public Map<Long, Long> initialize(Map<Long, Long> baselines) {
        if (baselines.isEmpty()) { return Map.of(); }
        List<Long> ids = new ArrayList<>(baselines.keySet());
        List<Object> values = pipeline(1, ids, id -> new byte[][] {
                bytes(totalKey()), bytes("INITIALIZE"), bytes(id.toString()), bytes(baselines.get(id).toString()) });
        if (values.size() != ids.size()) { throw new IllegalStateException("总量初始化未返回完整结果"); }
        Map<Long, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) { result.put(ids.get(i), count(Objects.toString(values.get(i), null))); }
        return result;
    }

    public Map<Long, Long> daily(LocalDate date) {
        Map<Object, Object> values = redis.opsForHash().entries(dailyKey(date));
        Map<Long, Long> result = new TreeMap<>();
        values.forEach((field, value) -> {
            try {
                long id = Long.parseLong(field.toString());
                if (id <= 0 || !Long.toString(id).equals(field.toString())) { throw new NumberFormatException(); }
                result.put(id, count(value.toString()));
            } catch (RuntimeException invalid) {
                log.warn("跳过非法文章日统计 date={} field={}", date, field);
            }
        });
        return result;
    }

    public void repair(LocalDate date, FlushResult result) {
        if (result.totalFloors().isEmpty()) { return; }
        pipeline(2, result.totalFloors().keySet(), id -> new byte[][] {
                bytes(dailyKey(date)), bytes(totalKey()), bytes("REPAIR"), bytes(id.toString()),
                bytes(result.totalFloors().get(id).toString()),
                bytes(result.dailyFloors().containsKey(id) ? result.dailyFloors().get(id).toString() : "") });
    }

    /** Pipeline中直接EVAL，避免脚本缓存未命中时在流水线内无法补发EVAL。 */
    private List<Object> pipeline(int keyCount, Collection<Long> ids,
                                  java.util.function.Function<Long, byte[][]> arguments) {
        byte[] script = bytes(OPERATE.getScriptAsString());
        return redis.executePipelined((RedisCallback<Object>) connection -> {
            for (Long id : ids) {
                byte[][] args = arguments.apply(id);
                connection.scriptingCommands().eval(script, ReturnType.VALUE, keyCount, args);
            }
            return null;
        });
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static long count(String value) {
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) { throw new IllegalStateException("浏览计数格式无效"); }
        return Long.parseLong(value);
    }
}
