package com.febrie.backend.article.like.infrastructure.observability;

import io.micrometer.core.instrument.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 标签仅使用固定结果/动作，不记录用户或文章ID，避免指标基数失控。 */
@Component
public class LikeTelemetry {
    private final MeterRegistry registry;
    public LikeTelemetry(MeterRegistry registry) { this.registry = registry; }
    public void event(String action, String result) { registry.counter("article.like.events", "action", action, "result", result).increment(); }
    public void time(String action, long started) {
        registry.timer("article.like.duration", "action", action).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    /** 分区编号来自固定拓扑，指标标签不会随文章数量增长。 */
    public void batch(int partition, int size) {
        registry.summary("article.like.batch.size", "partition", Integer.toString(partition)).record(size);
    }
    public void articleGroup(int partition, int original, int merged) {
        String value = Integer.toString(partition);
        registry.summary("article.like.group.size", "partition", value).record(merged);
        registry.counter("article.like.coalesced", "partition", value).increment(original - merged);
    }
    public void partitionEvent(int partition, String result) {
        registry.counter("article.like.partition.events", "partition", Integer.toString(partition), "result", result).increment();
    }
    public void articleTime(int partition, long started) {
        registry.timer("article.like.group.duration", "partition", Integer.toString(partition))
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
    public void queueAge(java.time.Instant occurredAt) {
        var age=java.time.Duration.between(occurredAt,java.time.Instant.now());
        registry.timer("article.like.queue.age").record(age.isNegative()?java.time.Duration.ZERO:age);
    }
}
