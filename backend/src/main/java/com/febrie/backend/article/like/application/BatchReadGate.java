package com.febrie.backend.article.like.application;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** 同进程按Key共享在途回源，领导者合并成一批SQL；超时不取消其他请求共享的工作。 */
public final class BatchReadGate<K,V> {
    private final ConcurrentMap<K,CompletableFuture<V>> flights = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Duration budget;
    public BatchReadGate(Executor executor, Duration budget) {
        this.executor = executor;
        this.budget = budget;
    }

    public Map<K,V> load(Collection<K> keys, Function<Collection<K>,Map<K,V>> loader) {
        long deadline=System.nanoTime()+budget.toNanos();
        Map<K,CompletableFuture<V>> selected=new LinkedHashMap<>(), leaders=new LinkedHashMap<>();
        for (K key:keys) {
            var mine=new CompletableFuture<V>();
            var previous=flights.putIfAbsent(key,mine);
            selected.put(key,previous==null?mine:previous);
            if (previous==null) { leaders.put(key,mine); }
        }
        if (!leaders.isEmpty()) {
            try {
                executor.execute(() -> {
                    try { finish(leaders, loader.apply(leaders.keySet()), null); }
                    catch (Throwable failure) { finish(leaders, null, failure); }
                });
            } catch (RuntimeException rejected) { finish(leaders, null, rejected); }
        }
        Map<K,V> result=new LinkedHashMap<>();
        try {
            for (var entry:selected.entrySet()) { result.put(entry.getKey(),entry.getValue().get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS)); }
            return result;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw LikeErrors.unavailable(); }
        catch (ExecutionException | TimeoutException failure) { throw LikeErrors.unavailable(); }
    }

    private void finish(Map<K,CompletableFuture<V>> leaders,Map<K,V> values,Throwable failure) {
        // SQL 已结束或任务提交失败，先清理整批键，再唤醒调用方，确保失败后可以立即重试。
        leaders.forEach((key, future) -> flights.remove(key, future));
        for (var entry:leaders.entrySet()) {
            V value=values==null?null:values.get(entry.getKey());
            if (failure!=null || value==null) { entry.getValue().completeExceptionally(failure==null?LikeErrors.unavailable():failure); }
            else { entry.getValue().complete(value); }
        }
    }
}
