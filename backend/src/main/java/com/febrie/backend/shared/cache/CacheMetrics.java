package com.febrie.backend.shared.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 缓存指标仅使用有限领域与结果标签，禁止携带用户或文章ID。 */
@Component
public class CacheMetrics {
    private final MeterRegistry registry;
    public CacheMetrics(MeterRegistry registry) { this.registry = registry; }
    public void record(String cache, String outcome) {
        registry.counter("community.cache.events", "cache", cache, "outcome", outcome).increment();
    }
}
