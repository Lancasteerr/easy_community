package com.febrie.backend.performance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 无依赖服务的定向测试，覆盖压测工具自己的数据与报告口径。 */
class BaselineToolTests {
    @TempDir Path root;

    @Test void seedAndScenariosAreReproducible() {
        var size = BaselineData.Size.STANDARD;
        assertEquals(500_000, size.likes());
        assertTrue(BaselineData.likesFor(size, 1) > BaselineData.likesFor(size, size.articles()));
        assertEquals(size.likes(), java.util.stream.LongStream.rangeClosed(1, size.articles())
                .map(id -> BaselineData.likesFor(size, id)).sum());
        assertEquals(BaselineData.article(size, 21), BaselineData.article(size, 21));
        var workload = BaselineData.workload(size);
        assertEquals(workload, BaselineData.workload(size));
        assertEquals(50_000, workload.partitions().stream().mapToInt(BaselineData.Partition::count).sum());
        for (int partition = 0; partition < BaselineData.PARTITIONS; partition++) {
            final int expected = partition;
            var group = workload.partitions().get(partition);
            assertTrue(java.util.stream.LongStream.range(0, group.count()).map(index -> group.first() + index * group.step())
                    .allMatch(article -> Math.floorMod(Long.hashCode(article), BaselineData.PARTITIONS) == expected));
        }
        assertEquals(14, BaselineK6.stages("Standard", "All").size());
        assertEquals(List.of(240, 240), BaselineK6.stages("Exploration", "All").stream().map(BaselineK6.Stage::load).toList());
        assertEquals(200, BaselineK6.stages("Standard", "Mixed").getLast().vus());
    }

    @Test void preservesPreciseVersionsAndRequiresFinalState() {
        var lower = new BaselineReconcile.Desired(9_007_199_254_740_992L, true, 3);
        var higher = new BaselineReconcile.Desired(9_007_199_254_740_993L, true, 4);
        assertTrue(BaselineReconcile.matches(higher, higher));
        assertFalse(BaselineReconcile.matches(higher, lower));
        assertFalse(BaselineReconcile.matches(higher, new BaselineReconcile.Desired(higher.version(), false, 0)));
        assertFalse(new BaselineReconcile.Audit(3, 0, 2, 0, 1).passed());
        assertFalse(new BaselineReconcile.Pv(100, 99, 99, 90).passed());
    }

    @Test void readsCompleteRecordsAndKeepsSkippedAndRateLimitedSamples() throws Exception {
        var json = new ObjectMapper();
        var file = root.resolve("requests.log");
        var event = new BaselineJournal.Event("like", 1, 6, 202, true, 1, 5, true, "9007199254740993", 12, "");
        var limited = new BaselineJournal.Event("like", 5, 9, 429, false, 2, 5, false, null, 2, "");
        Files.writeString(file, "EC_HTTP " + json.writeValueAsString(event) + "\nEC_HTTP " + json.writeValueAsString(limited));
        var journal = new BaselineJournal(file, json);
        journal.refresh();
        assertEquals(List.of(event), journal.events());
        Files.writeString(file, "\n", java.nio.file.StandardOpenOption.APPEND);
        journal.refresh();
        assertEquals(List.of(event, limited), journal.events());
        assertEquals(2, BaselineStats.overlap(journal.events()));
        assertEquals(2, BaselineStats.likeUsersInFlight(journal.events()));
        assertEquals(1, BaselineStats.likeUsersInFlight(List.of(event, event)));
        var route = BaselineStats.routes(journal.events(), 2).get("like");
        assertEquals(1, route.throttled());
        assertEquals(.5, route.rps());
        assertEquals(5, route.p99());

        var raw = root.resolve("metrics.json.gz");
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(raw))) {
            gzip.write("""
                    {"type":"Point","metric":"dropped_iterations","data":{"value":3}}
                    {"type":"Point","metric":"journey_completed","data":{"value":0}}
                    {"type":"Point","metric":"journey_completed","data":{"value":1}}
                    {"type":"Point","metric":"vus","data":{"value":10}}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new BaselineStats.K6(0, 3, 2, 1, 10), BaselineStats.k6(raw, json));
    }

    @Test void partialResultsExposeMissingOrResetCounters() throws Exception {
        assertNull(BaselineStats.delta(20, 10));
        assertEquals(4L, BaselineStats.delta(20, 24));
        assertTrue(BaselineStats.growing(List.of(1L, 1L, 6L, 7L, 19L, 20L)));
        assertFalse(BaselineStats.growing(List.of(1L, 15L, 2L, 17L, 1L, 15L)));
        var stage = BaselineReport.Stage.failed(1, new BaselineK6.Stage("detail", 50, 64), "采集缺失");
        BaselineReport.write(root, "Smoke", List.of(stage), false, "运行中断");
        var report = Files.readString(root.resolve("report.md"));
        assertTrue(report.contains("部分结果") && report.contains("采集缺失"));
        assertFalse(new ObjectMapper().readTree(root.resolve("summary.json").toFile()).path("complete").asBoolean());
    }
}
