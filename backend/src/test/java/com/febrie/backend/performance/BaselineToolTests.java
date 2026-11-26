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
        assertEquals(23, BaselineK6.stages("Standard", "All").size());
        assertEquals(List.of(240, 240), BaselineK6.stages("Exploration", "All").stream().map(BaselineK6.Stage::load).toList());
        assertEquals(200, BaselineK6.stages("Standard", "Mixed").getLast().vus());
    }

    @Test void resolvesCommentStages() {
        assertEquals(List.of("comment-read-5", "comment-write-spread-2", "comment-write-hot-2"),
                BaselineK6.stages("Smoke", "comment").stream().map(BaselineK6.Stage::directory).toList());
        assertEquals(List.of("comment-write-spread-20", "comment-write-hot-20"),
                BaselineK6.stages("Exploration", "CommentWrite").stream().map(BaselineK6.Stage::directory).toList());
        assertEquals(List.of(5, 5), BaselineK6.stages("Standard", "CommentWrite", List.of(5)).stream()
                .map(BaselineK6.Stage::load).toList());
        assertEquals(List.of("comment-reply-write-spread-2", "comment-reply-write-hot-2"),
                BaselineK6.stages("Smoke", "CommentReplyWrite").stream().map(BaselineK6.Stage::directory).toList());
        assertTrue(new BaselineCommentReplyWrite.Audit(2, 2, 0, 0, 0).passed());
        assertFalse(new BaselineCommentReplyWrite.Audit(2, 2, 0, 1, 0).passed());

    }

    @Test void reconcilesTheExactCommentIdReturnedByTheWrite() {
        var submit = new BaselineJournal.Event("comment-submit", 0, 20, 200, true, 1, 2, false,
                null, null, "", "a", "9007199254740993");
        var matching = new BaselineCommentWrite.Stored("a", 2, 1, 9007199254740993L, null, 2, 1, 0, false, "基线评论 a");
        var different = new BaselineCommentWrite.Stored("a", 2, 1, 9007199254740992L, null, 2, 1, 0, false, "基线评论 a");
        assertTrue(matching.matches(submit));
        assertFalse(different.matches(submit));
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

    @Test void resolvesExplicitRoundsAndRatesWithoutChangingProfileDefaults() {
        assertEquals(1, BaselineK6.rounds("Smoke", null));
        assertEquals(3, BaselineK6.rounds("Standard", null));
        assertEquals(2, BaselineK6.rounds("Smoke", "2"));
        assertThrows(IllegalArgumentException.class, () -> BaselineK6.rounds("Standard", "6"));
        for (String invalid : List.of("0", "1001", "1,01", "1,", "2.5", "-1")) {
            assertThrows(IllegalArgumentException.class, () -> BaselineK6.rates(invalid), invalid);
        }
        var rates = BaselineK6.rates("101,10");
        var detail = BaselineK6.stages("Standard", "Detail", rates);
        assertEquals(List.of("detail-cold-101", "detail-cold-10", "detail-101", "detail-10"),
                detail.stream().map(BaselineK6.Stage::directory).toList());
        var mixed = BaselineK6.stages("Smoke", "Mixed", rates).getFirst();
        assertEquals(BaselineK6.Model.JOURNEYS, mixed.model());
        assertEquals(404, mixed.vus());
        var probe = BaselineK6.stages("Smoke", "Like", List.of(1)).getLast();
        assertEquals("like-probe", probe.scenario());
        assertEquals(BaselineK6.Model.RPS, probe.model());
        assertEquals(500, BaselineK6.stages("Smoke", "Search", List.of(1000)).getFirst().vus());
    }

    @Test void readsCompleteRecordsAndKeepsSkippedAndRateLimitedSamples() throws Exception {
        var json = new ObjectMapper();
        var file = root.resolve("requests.log");
        var event = new BaselineJournal.Event("like", 1, 6, 202, true, 1, 5, true, "9007199254740993", 12, "", null, null);
        var limited = new BaselineJournal.Event("like", 5, 9, 429, false, 2, 5, false, null, 2, "", null, null);
        var legacy = (tools.jackson.databind.node.ObjectNode) json.valueToTree(event);
        legacy.remove("commentId");
        legacy.put("sample", false).putNull("outcome");
        Files.writeString(file, "EC_HTTP " + json.writeValueAsString(legacy) + "\nEC_HTTP " + json.writeValueAsString(limited));
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
        assertEquals(14L, route.responseBytes());
        var current = (tools.jackson.databind.node.ObjectNode) json.valueToTree(event);
        current.remove("bytes");
        var withoutBytes = json.treeToValue(current, BaselineJournal.Event.class);
        assertNull(BaselineStats.routes(List.of(withoutBytes), 2).get("like").responseBytes());

        var raw = root.resolve("metrics.json.gz");
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(raw))) {
            gzip.write("""
                    {"type":"Point","metric":"dropped_iterations","data":{"value":3}}
                    {"type":"Point","metric":"journey_completed","data":{"value":0}}
                    {"type":"Point","metric":"journey_completed","data":{"value":1}}
                    {"type":"Point","metric":"journey_started","data":{"value":1}}
                    {"type":"Point","metric":"vus","data":{"value":10}}
                    {"type":"Point","metric":"data_received","data":{"value":10}}
                    {"type":"Point","metric":"data_received","data":{"value":20}}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(new BaselineStats.K6(0, 3, 2, 1, 10, 1, 30L, 0, 0, java.util.Map.of()),
                BaselineStats.k6(raw, json, null));
    }

    @Test void alignsIterationAndHttpTimesWithoutCountingGraceAsPlannedLoad() throws Exception {
        var json = new ObjectMapper();
        long start = java.time.Instant.parse("2026-11-26T05:00:00Z").toEpochMilli();
        var log = root.resolve("timeline-http.log");
        var request = new BaselineJournal.Event("detail", start + 380, start + 2_001, 200, true,
                0, 1, false, null, null, "", null, null);
        Files.writeString(log, "EC_SCENARIO_START " + start + "\nEC_HTTP " + json.writeValueAsString(request) + "\n");
        var journal = new BaselineJournal(log, json);
        journal.refresh();
        assertEquals(start, journal.startedAt());
        var raw = root.resolve("timeline.json.gz");
        try (var gzip = new GZIPOutputStream(Files.newOutputStream(raw))) {
            gzip.write("""
                    {"type":"Point","metric":"iteration_started","data":{"time":"2026-11-26T13:00:00.120+08:00","value":2}}
                    {"type":"Point","metric":"dropped_iterations","data":{"time":"2026-11-26T13:00:00.123456789+08:00","value":1}}
                    {"type":"Point","metric":"iterations","data":{"time":"2026-11-26T05:00:02.020Z","value":1}}
                    {"type":"Point","metric":"http_reqs","data":{"value":1}}
                    {"type":"Point","metric":"iteration_duration","data":{"value":140.13}}
                    {"type":"Point","metric":"http_req_waiting","data":{"value":120.25}}
                    {"type":"Point","metric":"http_req_blocked","data":{"value":0.14}}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        var timeline = new BaselineTimeline(journal.startedAt(), 2, 3);
        var stats = BaselineStats.k6(raw, json, timeline);
        timeline.requests(journal.events());
        assertEquals(2, stats.startedIterations());
        assertEquals(1, stats.iterations());
        assertEquals(1, stats.dropped());
        assertEquals(1, stats.httpRequests());
        assertEquals(new BaselineStats.Latency(0.14, 0.14), stats.latency().get("http_req_blocked"));
        assertEquals(List.of(new BaselineTimeline.Second(0, "scheduled", 3, 2, 0, 1, 1, 0, 0),
                new BaselineTimeline.Second(1, "scheduled", 3, 0, 0, 0, 0, 0, 0),
                new BaselineTimeline.Second(2, "grace", 0, 0, 1, 0, 0, 1, 1)), timeline.rows());
        assertNull(new BaselineTimeline(start, 2, null).rows().getFirst().plannedIterations());
    }

    @Test void partialResultsExposeMissingOrResetCounters() throws Exception {
        assertNull(BaselineStats.delta(20, 10));
        assertEquals(4L, BaselineStats.delta(20, 24));
        assertTrue(BaselineStats.growing(List.of(1L, 1L, 6L, 7L, 19L, 20L)));
        assertFalse(BaselineStats.growing(List.of(1L, 15L, 2L, 17L, 1L, 15L)));
        var planned = new BaselineK6.Stage("detail", 50, 64);
        var stage = BaselineReport.Stage.failed(1, planned, "采集缺失");
        BaselineReport.write(root, "Smoke", new BaselineReport.Plan(2, List.of(planned)), List.of(stage), false, "运行中断");
        var report = Files.readString(root.resolve("report.md"));
        assertTrue(report.contains("部分结果") && report.contains("采集缺失"));
        assertFalse(new ObjectMapper().readTree(root.resolve("summary.json").toFile()).path("complete").asBoolean());
        assertEquals(2, new ObjectMapper().readTree(root.resolve("summary.json").toFile()).path("plan").path("rounds").asInt());
    }
}
