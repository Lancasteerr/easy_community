package com.febrie.backend.performance;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** 固定到达率发压；调度迟到或并发名额不足时显式记丢弃，不无限排队补发。 */
final class PerfLoad {
    @FunctionalInterface interface Request { int execute(int sequence) throws Exception; }
    private record Sample(int sequence, int status, long latencyNanos, long dispatchLagNanos) { }
    private PerfLoad() { }

    static Map<String, Object> run(String name, int rate, int seconds, int expectedStatus, Request request) throws Exception {
        var samples = new ConcurrentLinkedQueue<Sample>();
        var slots = new Semaphore(128);
        var dropped = new AtomicInteger();
        int planned = Math.multiplyExact(rate, seconds);
        long started = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int sequence = 0; sequence < planned; sequence++) {
                long due = started + sequence * 1_000_000_000L / rate;
                long remaining;
                while ((remaining = due - System.nanoTime()) > 0) { LockSupport.parkNanos(remaining); }
                long lag = Math.max(0, System.nanoTime() - due);
                if (lag > Duration.ofMillis(100).toNanos() || !slots.tryAcquire()) { dropped.incrementAndGet(); continue; }
                int number = sequence;
                executor.submit(() -> {
                    long began = System.nanoTime();
                    int status = 0;
                    try { status = request.execute(number); }
                    catch (Exception failure) { /* 网络/解码异常记为状态0，不能从延迟样本中删掉。 */ }
                    finally {
                        samples.add(new Sample(number, status, System.nanoTime() - began, lag));
                        slots.release();
                    }
                });
            }
            // 保持完整测量窗口；末尾在途请求的完成时间也计入吞吐分母。
            long remaining = started + Duration.ofSeconds(seconds).toNanos() - System.nanoTime();
            if (remaining > 0) { LockSupport.parkNanos(remaining); }
        }
        double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
        var ordered = samples.stream().sorted(Comparator.comparingInt(Sample::sequence)).toList();
        var latencies = ordered.stream().map(Sample::latencyNanos).sorted().toList();
        Map<Integer, Long> statuses = new TreeMap<>();
        ordered.forEach(sample -> statuses.merge(sample.status(), 1L, Long::sum));
        long success = statuses.getOrDefault(expectedStatus, 0L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", name); result.put("targetQps", rate); result.put("seconds", seconds);
        result.put("elapsedSeconds", elapsed); result.put("planned", planned); result.put("completed", ordered.size());
        result.put("success", success); result.put("dropped", dropped.get()); result.put("statusCounts", statuses);
        result.put("actualQps", ordered.size() / elapsed); result.put("successfulQps", success / elapsed);
        result.put("successRate", ordered.isEmpty() ? 0.0 : (double) success / ordered.size());
        result.put("p50Ms", percentile(latencies, .5)); result.put("p95Ms", percentile(latencies, .95));
        result.put("p99Ms", percentile(latencies, .99));
        result.put("dispatchLagP99Ms", percentile(ordered.stream().map(Sample::dispatchLagNanos).sorted().toList(), .99));
        Path output = output();
        StringBuilder csv = new StringBuilder("sequence,status,latencyNanos,dispatchLagNanos\n");
        ordered.forEach(sample -> csv.append(sample.sequence()).append(',').append(sample.status()).append(',')
                .append(sample.latencyNanos()).append(',').append(sample.dispatchLagNanos()).append('\n'));
        Files.writeString(output.resolve(name + ".csv"), csv);
        System.out.println("PERF_STAGE " + name + " qps=" + result.get("actualQps") + " p95=" + result.get("p95Ms")
                + " p99=" + result.get("p99Ms") + " statuses=" + statuses + " dropped=" + dropped);
        return result;
    }

    static Path output() throws Exception {
        Path path = Path.of(System.getenv().getOrDefault("IT_PERF_OUTPUT", "target/resume-performance"));
        Files.createDirectories(path); return path;
    }
    static double percentile(List<Long> sorted, double fraction) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * fraction) - 1)) / 1_000_000.0;
    }
}
