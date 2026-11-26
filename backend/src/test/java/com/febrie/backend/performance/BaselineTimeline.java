package com.febrie.backend.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** 停压后把迭代计数及可核验的 HTTP 起止归入同一秒轴。 */
final class BaselineTimeline {
    record Second(long second, String window, Integer plannedIterations, long startedIterations,
                  long completedIterations, long droppedIterations, long httpStarted, long httpCompleted,
                  long httpSuccessful) { }

    private static final class Counts {
        long started, completed, dropped, httpStarted, httpCompleted, httpSuccessful;
    }

    private final long startedAt;
    private final int seconds;
    private final Integer rate;
    private final TreeMap<Long, Counts> rows = new TreeMap<>();

    BaselineTimeline(long startedAt, int seconds, Integer rate) {
        this.startedAt = startedAt;
        this.seconds = seconds;
        this.rate = rate;
        // 零请求的秒也要保留，便于辨认发压间断。
        for (long second = 0; second < seconds; second++) { rows.put(second, new Counts()); }
    }

    void iteration(String metric, String at, long value) {
        var counts = at(Instant.parse(at).toEpochMilli());
        switch (metric) {
            case "iteration_started" -> counts.started += value;
            case "iterations" -> counts.completed += value;
            case "dropped_iterations" -> counts.dropped += value;
            default -> throw new IllegalArgumentException("未知的迭代指标 " + metric);
        }
    }

    void requests(List<BaselineJournal.Event> events) {
        for (var event : events) {
            at(event.start()).httpStarted++;
            var counts = at(event.end());
            counts.httpCompleted++;
            if (event.ok()) { counts.httpSuccessful++; }
        }
    }

    private Counts at(long time) {
        return rows.computeIfAbsent(Math.floorDiv(time - startedAt, 1000), ignored -> new Counts());
    }

    List<Second> rows() {
        return rows.entrySet().stream().map(entry -> {
            long second = entry.getKey();
            boolean scheduled = second >= 0 && second < seconds;
            var counts = entry.getValue();
            String window = scheduled ? "scheduled" : second < 0 ? "before" : "grace";
            Integer planned = rate == null ? null : Integer.valueOf(scheduled ? rate : 0);
            return new Second(second, window, planned, counts.started, counts.completed, counts.dropped,
                    counts.httpStarted, counts.httpCompleted, counts.httpSuccessful);
        }).toList();
    }

    void write(Path file, ObjectMapper json) throws IOException {
        try (var writer = Files.newBufferedWriter(file)) {
            for (var row : rows()) {
                writer.write(json.writeValueAsString(row));
                writer.newLine();
            }
        }
    }
}
