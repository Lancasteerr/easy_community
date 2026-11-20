package com.febrie.backend.performance;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.LikePartitionRouter;
import com.febrie.backend.article.view.application.ArticleViewService;
import com.febrie.backend.article.view.infrastructure.cache.ArticleViewStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import tools.jackson.databind.JsonNode;

/** 同步采样每项依赖并逐行落盘，单项失败保留其他指标和具体原因。 */
final class BaselineProbe implements AutoCloseable {
    record Queue(String name, long ready, long unacked, long published, long ack, long redeliver, long consumers) { }
    record Mq(List<Queue> partitions, long dead) {
        long pending() { return partitions.stream().mapToLong(q -> q.ready() + q.unacked()).sum(); }
        long ack() { return partitions.stream().mapToLong(Queue::ack).sum(); }
        long published() { return partitions.stream().mapToLong(Queue::published).sum(); }
        long redeliver() { return partitions.stream().mapToLong(Queue::redeliver).sum(); }
    }
    record Mysql(long waits, long waitMs, List<Map<String, Object>> blocking) { }
    record Sample(long at, String phase, Mq mq, Mysql mysql, Map<String, Object> es, Map<String, Number> app,
                  Map<String, Object> pv, Map<String, Object> coverage, double hostCpu, List<String> missing) { }

    private final BaselineEnvironment.Site site;
    private final BaselineReconcile reconcile;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final BufferedWriter output;
    private final List<String> queues;
    private final String dead;
    private final String rabbitAuth;
    private final String esAuth;
    private final com.sun.management.OperatingSystemMXBean os;
    private int samples;

    BaselineProbe(BaselineEnvironment.Site site, BaselineReconcile reconcile, Path file) throws IOException {
        this.site = site; this.reconcile = reconcile;
        output = Files.newBufferedWriter(file);
        var like = site.app.getBean(LikeProperties.class);
        queues = new LikePartitionRouter(like).queues(); dead = like.name("dead.q");
        rabbitAuth = basic("resume_test"); esAuth = basic("elastic");
        os = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
    }

    private String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + site.password()).getBytes(StandardCharsets.UTF_8));
    }

    Sample sample(String phase, boolean views) throws IOException {
        long now = System.currentTimeMillis();
        var missing = new ArrayList<String>();
        var mq = read("RabbitMQ", missing, this::mq);
        var mysql = read("MySQL", missing, this::mysql);
        var es = read("Elasticsearch", missing, this::es);
        var app = read("应用指标", missing, this::application);
        var coverage = read("SQL 覆盖采样", missing, () -> reconcile.sample(System.currentTimeMillis()));
        boolean includePv = views && (samples++ % 5 == 0 || !phase.equals("measure"));
        var pv = includePv ? read("PV 采样", missing, this::pv) : null;
        var sample = new Sample(now, phase, mq, mysql, es, app, pv, coverage, os == null ? -1 : os.getCpuLoad(), missing);
        output.write(site.json.writeValueAsString(sample));
        output.newLine();
        output.flush();
        return sample;
    }

    private Mq mq() throws Exception {
        var response = get("http://127.0.0.1:17973/api/queues/easy_community_resume_test", rabbitAuth);
        if (!response.isArray()) { throw new IllegalStateException("MQ 队列响应缺失"); }
        var found = new LinkedHashMap<String, Queue>();
        long deadCount = -1;
        for (var item : response) {
            String name = item.path("name").asString();
            if (!name.equals(dead) && !queues.contains(name)) { continue; }
            if (item.path("messages_ready").isMissingNode() || item.path("messages_unacknowledged").isMissingNode()) {
                throw new IllegalStateException("MQ 尚未返回队列统计");
            }
            long ready = item.path("messages_ready").asLong(), unacked = item.path("messages_unacknowledged").asLong();
            if (name.equals(dead)) { deadCount = ready + unacked; continue; }
            var stats = item.path("message_stats");
            found.put(name, new Queue(name, ready, unacked, stats.path("publish").asLong(),
                    stats.path("ack").asLong(), stats.path("redeliver").asLong(), item.path("consumers").asLong()));
        }
        if (found.size() != queues.size() || deadCount < 0) { throw new IllegalStateException("未采齐命令分区和死信队列"); }
        return new Mq(queues.stream().map(found::get).toList(), deadCount);
    }

    private Mysql mysql() {
        var counters = new TreeMap<String, Long>(String.CASE_INSENSITIVE_ORDER);
        site.jdbc.query("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time')",
                row -> { counters.put(row.getString(1), row.getLong(2)); });
        var locks = site.jdbc.queryForList("""
                SELECT req.OBJECT_NAME AS tableName, req.INDEX_NAME AS indexName,
                       w.REQUESTING_ENGINE_TRANSACTION_ID AS requestedTransaction,
                       w.BLOCKING_ENGINE_TRANSACTION_ID AS blockingTransaction
                  FROM performance_schema.data_lock_waits w
                  JOIN performance_schema.data_locks req ON req.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                 WHERE req.OBJECT_SCHEMA=DATABASE() LIMIT 100
                """);
        return new Mysql(counters.get("Innodb_row_lock_waits"), counters.get("Innodb_row_lock_time"), locks);
    }

    private Map<String, Object> es() throws Exception {
        var index = get("http://127.0.0.1:19300/" + site.index() + "/_stats/search?filter_path=_all.total.search", esAuth)
                .path("_all").path("total").path("search");
        var nodes = get("http://127.0.0.1:19300/_nodes/stats/thread_pool?filter_path=nodes.*.thread_pool.search*", esAuth).path("nodes");
        if (!index.isObject() || !nodes.isObject()) { throw new IllegalStateException("ES 搜索统计缺失"); }
        long rejected = 0;
        for (var node : nodes) {
            for (var pool : node.path("thread_pool")) { rejected += pool.path("rejected").asLong(); }
        }
        return Map.of("queries", index.path("query_total").asLong(), "queryMs", index.path("query_time_in_millis").asLong(),
                "rejected", rejected, "current", index.path("query_current").asLong(),
                "openContexts", index.path("open_contexts").asLong());
    }

    private Map<String, Number> application() {
        var values = new TreeMap<String, Number>();
        for (var meter : site.app.getBean(MeterRegistry.class).getMeters()) {
            var id = meter.getId();
            String name = id.getName();
            if (!(name.startsWith("article.") || name.startsWith("hikaricp.") || name.startsWith("jvm.gc.")
                    || name.startsWith("baseline.pv.") || name.equals("community.cache.events"))) { continue; }
            String key = id.getTags().isEmpty() ? name : name + id.getTags();
            for (var statistic : meter.measure()) {
                if (Double.isFinite(statistic.getValue())) {
                    values.put(key + "." + statistic.getStatistic().name().toLowerCase(java.util.Locale.ROOT),
                            statistic.getValue());
                }
            }
        }
        if (values.keySet().stream().noneMatch(key -> key.startsWith("hikaricp."))) {
            throw new IllegalStateException("连接池应用指标缺失");
        }
        site.app.getBean(PerfSqlCounter.class).snapshot().forEach((statement, count) ->
                values.put("sql.mapper." + statement, count));
        return values;
    }

    private Map<String, Object> pv() {
        var today = LocalDate.ofInstant(Instant.now(), ArticleViewService.BUSINESS_ZONE);
        var store = site.app.getBean(ArticleViewStore.class);
        long redisTotal = store.daily(today).values().stream().mapToLong(Long::longValue).sum()
                + store.daily(today.minusDays(1)).values().stream().mapToLong(Long::longValue).sum();
        long sql = reconcile.persistedViews();
        return Map.of("redisDailyTotal", redisTotal, "sqlTotal", sql, "pending", Math.max(0, redisTotal - sql),
                "httpResponses", reconcile.views());
    }

    private JsonNode get(String url, String auth) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                .header("Authorization", auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) { throw new IllegalStateException("HTTP " + response.statusCode()); }
        return site.json.readTree(response.body());
    }

    private static <T> T read(String source, List<String> errors, Callable<T> query) {
        try { return query.call(); }
        catch (Exception error) {
            errors.add(source + "：" + error.getClass().getSimpleName());
            return null;
        }
    }

    @Override public void close() throws IOException { try (http; output) { } }
}
