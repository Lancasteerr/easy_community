package com.febrie.backend.performance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.ObjectMapper;

/** 统一从原始请求与 k6 采样计算汇总，聚合分位数始终使用原始值。 */
final class BaselineStats {
    record Route(long total, long success, long throttled, double rps, long p50, long p95, long p99,
                 long responseBytes, Map<Integer, Long> statuses) { }
    record K6(long iterations, long dropped, long journeys, long successfulJourneys, long peakVus) { }
    private record Edge(long at, int change, long user) { }

    private BaselineStats() { }

    static Map<String, Route> routes(List<BaselineJournal.Event> events, double seconds) {
        var grouped = new TreeMap<String, List<BaselineJournal.Event>>();
        for (var event : events) { grouped.computeIfAbsent(event.name(), ignored -> new ArrayList<>()).add(event); }
        var result = new TreeMap<String, Route>();
        grouped.forEach((name, requests) -> {
            long success = requests.stream().filter(BaselineJournal.Event::ok).count();
            var statuses = new TreeMap<Integer, Long>();
            requests.forEach(event -> statuses.merge(event.status(), 1L, Long::sum));
            var latency = requests.stream().map(event -> event.end() - event.start()).sorted().toList();
            result.put(name, new Route(requests.size(), success, statuses.getOrDefault(429, 0L), success / seconds,
                    percentile(latency, .5), percentile(latency, .95), percentile(latency, .99),
                    requests.stream().mapToLong(BaselineJournal.Event::bytes).sum(), statuses));
        });
        return result;
    }

    static long percentile(List<Long> sorted, double fraction) {
        return sorted.isEmpty() ? 0 : sorted.get((int) Math.ceil(sorted.size() * fraction) - 1);
    }

    static int overlap(List<BaselineJournal.Event> events) {
        return overlap(events, false);
    }

    static int likeUsersInFlight(List<BaselineJournal.Event> events) {
        return overlap(events.stream().filter(event -> event.name().equals("like") && event.user() > 0).toList(), true);
    }

    private static int overlap(List<BaselineJournal.Event> events, boolean distinctUsers) {
        var edges = new ArrayList<Edge>();
        for (var event : events) {
            edges.add(new Edge(event.start(), 1, event.user()));
            // 毫秒内零时长样本只计作单点请求；相邻非零窗口不算重叠。
            edges.add(new Edge(Math.max(event.start() + 1, event.end()), -1, event.user()));
        }
        edges.sort(Comparator.comparingLong(Edge::at).thenComparingInt(Edge::change));
        var users = new HashMap<Long, Integer>();
        int peak = 0, running = 0;
        for (var edge : edges) {
            if (distinctUsers) {
                int count = users.getOrDefault(edge.user(), 0) + edge.change();
                if (count == 0) { users.remove(edge.user()); }
                else { users.put(edge.user(), count); }
                peak = Math.max(peak, users.size());
            } else {
                running += edge.change();
                peak = Math.max(peak, running);
            }
        }
        return peak;
    }

    static K6 k6(Path raw, ObjectMapper json) throws IOException {
        long iterations = 0, dropped = 0, journeys = 0, success = 0, vus = 0;
        try (var reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(raw)),
                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                var node = json.readTree(line);
                if (!node.path("type").asString().equals("Point")) { continue; }
                long value = node.path("data").path("value").asLong();
                switch (node.path("metric").asString()) {
                    case "iterations" -> iterations += value;
                    case "dropped_iterations" -> dropped += value;
                    case "journey_completed" -> { journeys++; success += value; }
                    case "vus" -> vus = Math.max(vus, value);
                    default -> { /* 延迟和状态使用可与业务对账关联的请求记录。 */ }
                }
            }
        }
        return new K6(iterations, dropped, journeys, success, vus);
    }

    static Long delta(long first, long last) { return last < first ? null : last - first; }

    static boolean growing(List<Long> depth) {
        if (depth.size() < 6) { return false; }
        int width = depth.size() / 3;
        double early = average(depth, 0, width), middle = average(depth, width, width * 2);
        double late = average(depth, width * 2, depth.size());
        // 三段均值连续上升，且末段较首段至少多 10 条，过滤抓取抖动。
        return middle > early && late > middle && late - early >= 10;
    }

    private static double average(List<Long> values, int from, int to) {
        return values.subList(from, to).stream().mapToLong(Long::longValue).average().orElse(0);
    }
}
