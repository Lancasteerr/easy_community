package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.redis.LikeCache;

import com.febrie.backend.article.like.domain.LikeState;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 共享内容取出后才批量装配个人状态；认证和文章内容缓存仍保留原有依赖边界。 */
@Service
@Transactional(propagation=Propagation.NEVER)
public class LikeQueryService implements DisposableBean {
    private final LikeCache cache;
    private final LikeReader reader;
    private final LikeTelemetry telemetry;
    private final ExecutorService executor;
    private final BatchReadGate<Long,Long> counts;
    private final BatchReadGate<StateKey,LikeState> states;
    public LikeQueryService(LikeCache cache,LikeReader reader,LikeProperties properties,LikeTelemetry telemetry) {
        this.cache=cache; this.reader=reader; this.telemetry=telemetry;
        // 每个回源批次使用一个虚拟线程，实际 SQL 并发由数据源连接池控制。
        executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("like-read-", 0).factory());
        counts = new BatchReadGate<>(executor, properties.readTimeout());
        states = new BatchReadGate<>(executor, properties.readTimeout());
    }
    public Map<Long,Summary> summaries(Long user,Collection<Long> requested) {
        var ids=new LinkedHashSet<>(requested);
        if (ids.size()>50 || ids.stream().anyMatch(id -> id==null || id<=0)) { throw LikeErrors.invalid(); }
        if (ids.isEmpty()) { return Map.of(); }
        long started=System.nanoTime();
        try {
            Map<Long,Long> totals=new LinkedHashMap<>();
            try { totals.putAll(cache.counts(ids)); }
            catch (RuntimeException unavailable) { telemetry.event("cache","count_failure"); }
            var missing=ids.stream().filter(id -> !totals.containsKey(id)).toList();
            telemetry.event("cache",missing.isEmpty()?"count_hit":"count_miss");
            var loadedCounts=counts.load(missing,keys -> {
                telemetry.event("read","count_sql"); return reader.counts(keys);
            });
            totals.putAll(loadedCounts);
            // SQL 等待结束后再回填 Redis，慢回填不会触发 SQL 等待超时。
            if(!loadedCounts.isEmpty()){
                try { cache.putCounts(loadedCounts); } catch (RuntimeException ignored) { telemetry.event("cache","fill_failure"); }
            }
            Map<Long,LikeState> mine=new LinkedHashMap<>();
            if (user!=null) {
                try { mine.putAll(cache.states(user,ids)); }
                catch (RuntimeException unavailable) { telemetry.event("cache","state_failure"); }
                var needed=ids.stream().filter(id -> !mine.containsKey(id)).map(id -> new StateKey(user,id)).toList();
                var loaded=states.load(needed,keys -> {
                    // 一次请求的领导者只属于同一用户，缺失关系由Reader补成version=0。
                    var articles=keys.stream().map(StateKey::article).toList();
                    telemetry.event("read","state_sql"); var sql=reader.states(user,articles);
                    Map<StateKey,LikeState> result=new LinkedHashMap<>();
                    for (var key:keys) { result.put(key,sql.get(key.article())); }
                    return result;
                });
                Map<Long,LikeState> sqlStates=new LinkedHashMap<>();
                loaded.forEach((key,state) -> sqlStates.put(key.article(),state));
                if(!sqlStates.isEmpty()){
                    try { mine.putAll(cache.putStates(user,sqlStates.values(),"C")); }
                    catch(RuntimeException ignored){telemetry.event("cache","fill_failure");mine.putAll(sqlStates);}
                }
            }
            Map<Long,Summary> result=new LinkedHashMap<>();
            for (long id:ids) { result.put(id,new Summary(totals.get(id),user!=null && mine.get(id).liked())); }
            return result;
        } finally { telemetry.time("read",started); }
    }
    @Override public void destroy() { executor.shutdownNow(); }
    public record Summary(long likeCount,boolean likedByMe) { }
    private record StateKey(long user,long article) { }
}
