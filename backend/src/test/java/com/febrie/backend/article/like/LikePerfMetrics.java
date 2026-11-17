package com.febrie.backend.article.like;

import com.febrie.backend.performance.PerfSqlCounter;
import io.micrometer.core.instrument.*;
import java.util.*;

/** 两个上下文各自取快照再求差，不能只观察单活消费者较多的第一个实例。 */
final class LikePerfMetrics {
    static List<Map<String, Double>> snapshot(LikePerfFixture fixture) {
        List<Map<String, Double>> snapshots = new ArrayList<>();
        for (var app : fixture.apps) {
            Map<String, Double> values = new TreeMap<>();
            app.getBean(PerfSqlCounter.class).snapshot().forEach((key, value) -> values.put("sql." + key, value.doubleValue()));
            var registry = app.getBean(MeterRegistry.class);
            values.put("groups", registry.find("article.like.partition.events").tag("result", "committed").counters().stream().mapToDouble(Counter::count).sum());
            values.put("coalesced", registry.find("article.like.coalesced").counters().stream().mapToDouble(Counter::count).sum());
            values.put("relations", registry.find("article.like.group.size").summaries().stream().mapToDouble(DistributionSummary::totalAmount).sum());
            values.put("batches", registry.find("article.like.batch.size").summaries().stream().mapToDouble(DistributionSummary::count).sum());
            values.put("batchMessages", registry.find("article.like.batch.size").summaries().stream().mapToDouble(DistributionSummary::totalAmount).sum());
            values.put("sqlAttempts", registry.find("article.like.duration").tag("action", "sql").timers().stream().mapToDouble(io.micrometer.core.instrument.Timer::count).sum());
            for (String outcome : List.of("applied", "duplicate", "stale", "retry")) {
                values.put(outcome, registry.find("article.like.events").tags("action", "consume", "result", outcome).counters().stream().mapToDouble(Counter::count).sum());
            }
            snapshots.add(values);
        }
        return snapshots;
    }
    static Map<String, Object> delta(LikePerfFixture fixture, List<Map<String, Double>> before) {
        var after = snapshot(fixture); List<Map<String, Double>> instances = new ArrayList<>();
        Map<String, Double> total = new TreeMap<>();
        for (int i = 0; i < after.size(); i++) {
            Map<String, Double> values = new TreeMap<>();
            int instance = i;
            after.get(i).forEach((key, value) -> { double difference = value - before.get(instance).getOrDefault(key, 0.0); values.put(key, difference); total.merge(key, difference, Double::sum); });
            instances.add(values);
        }
        double original = total.getOrDefault("relations", 0.0) + total.getOrDefault("coalesced", 0.0);
        return Map.of("instances", instances, "total", total, "coalescingRatio", original == 0 ? 0 : total.getOrDefault("coalesced", 0.0) / original);
    }
}
