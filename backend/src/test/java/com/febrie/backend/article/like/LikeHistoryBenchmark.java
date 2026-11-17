package com.febrie.backend.article.like;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;

/** 只测新版；60/120保留历史顺序，240探索放在恢复阶段之后。 */
final class LikeHistoryBenchmark {
    void run() throws Exception {
        int seconds = Integer.parseInt(System.getenv().getOrDefault("IT_LIKE_LOAD_SECONDS", "60"));
        assertTrue(seconds >= 3 && seconds <= 300);
        Path output = Path.of(System.getenv("IT_PERF_OUTPUT")); Files.createDirectories(output);
        var report = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> measurements = new ArrayList<>();
        report.put("schemaVersion", 2); report.put("sampleSeconds", seconds); report.put("measurements", measurements);
        report.put("sqlAuditConsistent", false); report.put("targetAuditConsistent", false);
        report.put("status", "failed"); report.put("workload", "legacy_rotating_16_users_32_articles");
        report.put("qualification", "仅新版与跨时间历史样本对比；不是同步配对实验；不证明生产极限容量");
        try (var fixture = new LikePerfFixture(); var observer = new LikePerfObserver(fixture)) {
            report.put("namespace", fixture.properties.namespace());
            report.put("java", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors()); report.put("applications", 2);
            report.put("mysql", fixture.jdbc.queryForObject("SELECT VERSION()", String.class));
            report.put("mysqlDurability", fixture.jdbc.queryForMap("SELECT @@innodb_flush_log_at_trx_commit AS flushAtCommit, @@sync_binlog AS syncBinlog"));
            report.put("partitionCount", 4); report.put("batchSize", 20); report.put("batchWaitMs", 100); report.put("prefetch", 20);
            report.put("coveragePollingMs", 250); report.put("brokerPollingMs", 1000);
            report.put("brokerStatistics", "管理API异步统计，Broker默认约5秒刷新；不作为亚秒级ACK时间");
            List<Map<String, Object>> instances = new ArrayList<>();
            for (int i = 0; i < fixture.apps.size(); i++) {
                var app = fixture.apps.get(i);
                try (var connection = app.getBean(ConnectionFactory.class).createConnection()) {
                    instances.add(Map.of("instance", i, "httpPort", fixture.port(i), "brokerLocalPort", connection.getLocalPort()));
                }
            }
            report.put("instances", instances); report.put("consumersBefore", observer.broker.consumers());
            for (String scenario : List.of("uniform", "hot")) {
                for (int rate : List.of(60, 120)) { execute(fixture, observer, measurements, scenario, rate, seconds, output, false); }
            }
            // 保持历史脚本的512次读预热及清展示缓存位置，不把这部分当作写请求样本。
            fixture.warmReadMatrix();
            execute(fixture, observer, measurements, "backlog", 60, 5, output, true);
            for (String scenario : List.of("uniform_explore", "hot_explore")) {
                execute(fixture, observer, measurements, scenario, 240, seconds, output, false);
            }
            observer.audit();
            report.put("sqlAuditConsistent", true); report.put("targetAuditConsistent", true);
            report.put("consumersAfter", observer.broker.consumers()); report.put("status", "measured");
            fixture.successful = true;
        } finally {
            report.put("recordedAt", Instant.now().toString());
            var mapper = new tools.jackson.databind.ObjectMapper();
            Files.writeString(output.resolve("like-capacity.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    private void execute(LikePerfFixture fixture, LikePerfObserver observer, List<Map<String, Object>> measurements,
                         String scenario, int rate, int seconds, Path output, boolean backlog) throws Exception {
        String name = "like-" + scenario + "-" + rate;
        observer.begin(name);
        observer.drain(System.nanoTime());
        // 前一阶段已确认完成后才取得本阶段计数快照。
        var before = LikePerfMetrics.snapshot(fixture); var lockBefore = lockStatistics(fixture);
        var result = new LinkedHashMap<String, Object>(); measurements.add(result);
        result.put("scenario", scenario); result.put("targetQps", rate);
        result.put("purpose", scenario.endsWith("explore") ? "new_only" : "historical_comparison");
        result.put("drained", false);
        try {
            if (backlog) { fixture.consumers(false); }
            LikePerfLoad.measure(fixture, observer, scenario, rate, seconds, backlog ? 300 : 0, output, result);
            var lockAfter = lockStatistics(fixture);
            result.put("sqlRowLockWaits", lockAfter.get("Innodb_row_lock_waits") - lockBefore.get("Innodb_row_lock_waits"));
            result.put("sqlRowLockWaitMs", lockAfter.get("Innodb_row_lock_time") - lockBefore.get("Innodb_row_lock_time"));
            long resumed = System.nanoTime();
            if (backlog) { fixture.consumers(true); }
            var drain = observer.drain(resumed); result.putAll(drain);
            if (backlog) {
                var recovery = new LinkedHashMap<String, Object>(); recovery.put("scenario", "backlog_recovery");
                recovery.put("milliseconds", drain.get("coverageDrainMs")); recovery.put("physicalDrainMs", drain.get("physicalDrainMs"));
                recovery.put("backlogComparable", result.get("backlogComparable")); measurements.add(recovery);
            }
            result.put("metrics", LikePerfMetrics.delta(fixture, before)); observer.audit();
            result.put("targetAuditConsistent", true);
        } finally { observer.save(output, result); }
        System.out.println("LIKE_LOAD_STAGE " + scenario + " rate=" + rate + " accepted=" + result.get("success")
                + " pending=" + result.get("acceptedPending") + " coverageDrainMs=" + result.get("coverageDrainMs"));
    }
    private Map<String, Long> lockStatistics(LikePerfFixture fixture) {
        Map<String, Long> result = new HashMap<>();
        fixture.jdbc.query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')",
                rs -> { result.put(rs.getString(1), Long.parseLong(rs.getString(2))); }); return result;
    }
}
