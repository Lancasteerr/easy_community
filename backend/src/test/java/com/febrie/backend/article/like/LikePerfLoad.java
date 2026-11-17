package com.febrie.backend.article.like;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 保留旧发压与文章选择规则，只增加原始字段；忙跳过和失败不会从样本中隐藏。 */
final class LikePerfLoad {
    static void measure(LikePerfFixture fixture, LikePerfObserver observer, String scenario, int rate,
                        int seconds, int fixedRequests, Path output, Map<String, Object> result) throws Exception {
        var samples = new ConcurrentLinkedQueue<Sample>();
        AtomicInteger sequence = new AtomicInteger(), skipped = new AtomicInteger(), planned = new AtomicInteger();
        long period = 1_000_000_000L / rate, began = System.nanoTime();
        var fixedDone = new CountDownLatch(1);
        try (var work = Executors.newVirtualThreadPerTaskExecutor(); var clock = Executors.newSingleThreadScheduledExecutor()) {
            var ticking = clock.scheduleAtFixedRate(() -> {
                int number = sequence.getAndIncrement();
                if (fixedRequests > 0 && number >= fixedRequests) { return; }
                planned.incrementAndGet();
                try {
                    int userIndex = number % fixture.clients.size();
                    var client = fixture.clients.get(userIndex);
                    if (!client.available.tryAcquire()) { skipped.incrementAndGet(); return; }
                    work.submit(() -> {
                        long started = System.nanoTime(), wall = System.currentTimeMillis();
                        int articleIndex = scenario.startsWith("hot") ? 0 : (number / fixture.clients.size()) % fixture.articles.size();
                        long article = fixture.articles.get(articleIndex), version = 0;
                        boolean liked = client.next(article); int status = 0;
                        try {
                            var response = client.send(number % fixture.apps.size(), liked ? "PUT" : "DELETE",
                                    "/api/articles/" + article + "/like", null, true);
                            status = response.statusCode();
                            if (status == 202) {
                                version = Long.parseLong(fixture.mapper.readTree(response.body()).path("version").asString());
                                observer.accepted(userIndex, articleIndex, version, liked, started);
                            }
                        } catch (Exception failure) { status = 0; }
                        finally {
                            long ended = System.nanoTime();
                            samples.add(new Sample(number, userIndex, articleIndex, number * period, started - began,
                                    ended - began, ended - started, status, liked, version, wall));
                            client.available.release();
                        }
                    });
                } finally { if (fixedRequests > 0 && number == fixedRequests - 1) { fixedDone.countDown(); } }
            }, 0, period, TimeUnit.NANOSECONDS);
            if (fixedRequests > 0) {
                if (!fixedDone.await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("固定积压发压调度超时"); }
            } else { new CountDownLatch(1).await(seconds, TimeUnit.SECONDS); }
            ticking.cancel(false);
        }
        long completedAt = System.nanoTime();
        observer.sampleSql();
        var values = samples.stream().map(Sample::latencyNanos).sorted().toList();
        Map<Integer, Long> statuses = new TreeMap<>();
        samples.forEach(sample -> statuses.merge(sample.status(), 1L, Long::sum));
        long success = statuses.getOrDefault(202, 0L);
        result.put("scenario", scenario); result.put("targetQps", rate); result.put("sampleSeconds", seconds);
        result.put("recordedAt", Instant.now().toString()); result.put("planned", planned.get());
        result.put("completed", values.size()); result.put("success", success); result.put("skippedBusyClient", skipped.get());
        // 保留旧字段的分母位置：包括任务收尾和一次SQL覆盖采样；另记精确的发压窗口。
        double elapsed = (System.nanoTime() - began) / 1_000_000_000.0;
        result.put("actualQps", values.size() / elapsed); result.put("successQps", success / elapsed);
        result.put("requestWindowSeconds", (completedAt - began) / 1_000_000_000.0);
        result.put("elapsedSeconds", elapsed); result.put("statusCounts", statuses);
        result.put("p50Ms", percentile(values, .5)); result.put("p95Ms", percentile(values, .95)); result.put("p99Ms", percentile(values, .99));
        result.put("acceptedPending", observer.pending.size());
        result.put("backlogComparable", fixedRequests <= 0 || (success == fixedRequests && skipped.get() == 0));
        StringBuilder csv = new StringBuilder("request,userIndex,articleIndex,plannedOffsetNanos,startedOffsetNanos,endedOffsetNanos,latencyNanos,status,liked,version,startedAtEpochMillis\n");
        for (var s : samples) {
            csv.append(s.request()).append(',').append(s.userIndex()).append(',').append(s.articleIndex()).append(',')
                    .append(s.plannedOffsetNanos()).append(',').append(s.startedOffsetNanos()).append(',').append(s.endedOffsetNanos()).append(',')
                    .append(s.latencyNanos()).append(',').append(s.status()).append(',').append(s.liked()).append(',').append(s.version()).append(',').append(s.startedAtEpochMillis()).append('\n');
        }
        Files.writeString(output.resolve("like-" + scenario + "-" + rate + ".csv"), csv.toString());
    }
    static double percentile(List<Long> values, double fraction) {
        return values.isEmpty() ? 0 : values.get((int) Math.ceil(values.size() * fraction) - 1) / 1_000_000.0;
    }
    private record Sample(int request, int userIndex, int articleIndex, long plannedOffsetNanos,
            long startedOffsetNanos, long endedOffsetNanos, long latencyNanos, int status, boolean liked, long version, long startedAtEpochMillis) { }
}
