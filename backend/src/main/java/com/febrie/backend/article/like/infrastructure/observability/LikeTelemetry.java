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
    public void queueAge(java.time.Instant occurredAt) {
        var age=java.time.Duration.between(occurredAt,java.time.Instant.now());
        registry.timer("article.like.queue.age").record(age.isNegative()?java.time.Duration.ZERO:age);
    }
}
