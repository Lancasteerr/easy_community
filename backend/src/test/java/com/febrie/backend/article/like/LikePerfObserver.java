package com.febrie.backend.article.like;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** SQL版本覆盖与物理队列排空分别观测；失败采样不能当作零。 */
final class LikePerfObserver implements AutoCloseable {
    final LikePerfFixture fixture;
    final LikePerfBroker broker;
    final AtomicLong accepted = new AtomicLong();
    final ConcurrentMap<Pending, Long> pending = new ConcurrentHashMap<>();
    final ConcurrentMap<Relation, Target> targets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sampler = Executors.newScheduledThreadPool(2);
    volatile Stage current;
    LikePerfObserver(LikePerfFixture fixture) throws Exception {
        this.fixture = fixture; broker = new LikePerfBroker(fixture); broker.sample();
        sampler.scheduleWithFixedDelay(this::sampleSql, 0, 250, TimeUnit.MILLISECONDS);
        sampler.scheduleWithFixedDelay(this::sampleBroker, 0, 1, TimeUnit.SECONDS);
    }
    void begin(String name) { current = new Stage(name); }
    void accepted(int user, int article, long version, boolean liked, long started) {
        pending.put(new Pending(user, article, version), started);
        targets.merge(new Relation(user, article), new Target(version, liked), (old, next) -> old.version() >= next.version() ? old : next);
        accepted.incrementAndGet();
    }
    synchronized void sampleSql() {
        var stage = current; if (stage == null) { return; }
        if (pending.isEmpty()) { stage.pendingSeries.add(Map.of("at", Instant.now().toString(), "pending", 0, "oldestMs", 0)); return; }
        try {
            Map<Relation, Target> states = states();
            long now = System.nanoTime();
            pending.forEach((key, sent) -> {
                var actual = states.get(new Relation(key.user(), key.article()));
                if (actual != null && actual.version() >= key.version() && pending.remove(key, sent)) {
                    stage.coverage.add(new Coverage(key.user(), key.article(), key.version(), actual.version(), now - sent));
                }
            });
            long oldest = pending.values().stream().mapToLong(sent -> now - sent).max().orElse(0) / 1_000_000;
            stage.pendingSeries.add(Map.of("at", Instant.now().toString(), "pending", pending.size(), "oldestMs", oldest));
        } catch (RuntimeException error) { stage.pendingSeries.add(Map.of("at", Instant.now().toString(), "samplingError", true)); }
    }
    private void sampleBroker() {
        var stage = current; if (stage == null) { return; }
        try { stage.brokerSeries.add(broker.sample()); }
        catch (Exception error) { stage.brokerSeries.add(Map.of("at", Instant.now().toString(), "samplingError", true)); }
    }
    Map<Relation, Target> states() {
        Map<Long, Integer> users = new HashMap<>(), articles = new HashMap<>();
        for (int i = 0; i < fixture.users.size(); i++) { users.put(fixture.users.get(i), i); }
        for (int i = 0; i < fixture.articles.size(); i++) { articles.put(fixture.articles.get(i), i); }
        String marks = String.join(",", Collections.nCopies(fixture.articles.size(), "?"));
        Map<Relation, Target> result = new HashMap<>();
        fixture.jdbc.query("SELECT user_id,article_id,version,liked FROM article_like_state WHERE article_id IN (" + marks + ")",
                rs -> { result.put(new Relation(users.get(rs.getLong(1)), articles.get(rs.getLong(2))), new Target(rs.getLong(3), rs.getBoolean(4))); }, fixture.articles.toArray());
        return result;
    }
    /** 同时要求SQL覆盖和累计ACK达到受理量，防止Broker统计刷新前的假零。 */
    Map<String, Object> drain(long began) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120), coverageMs = -1;
        while (System.nanoTime() < deadline) {
            sampleSql();
            if (coverageMs < 0 && pending.isEmpty()) { coverageMs = (System.nanoTime() - began) / 1_000_000; }
            var state = broker.sample(); current.brokerSeries.add(state);
            if (pending.isEmpty() && state.ready() == 0 && state.unacked() == 0 && state.ack() >= accepted.get() && state.dead() == 0) {
                return Map.of("coverageDrainMs", coverageMs, "physicalDrainMs", (System.nanoTime() - began) / 1_000_000,
                        "brokerFinal", state, "drained", true);
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("120秒内未完成SQL覆盖与MQ排空，保留本轮证据");
    }
    void audit() {
        var actual = states();
        targets.forEach((key, target) -> assertEquals(target, actual.get(key), "最终状态和最高受理版本不一致"));
        for (long article : fixture.articles) {
            long expected = fixture.jdbc.queryForObject("SELECT COUNT(*) FROM article_like_state WHERE article_id=? AND liked=1", Long.class, article);
            assertEquals(expected, fixture.jdbc.queryForObject("SELECT like_count FROM article_like_count WHERE article_id=?", Long.class, article));
        }
    }
    void save(Path output, Map<String, Object> result) throws Exception {
        var stage = current;
        result.put("pendingSeries", List.copyOf(stage.pendingSeries)); result.put("brokerSeries", List.copyOf(stage.brokerSeries));
        result.put("coverageSamples", stage.coverage.size());
        result.put("pendingSamplingErrors", stage.pendingSeries.stream().filter(s -> s.containsKey("samplingError")).count());
        result.put("brokerSamplingErrors", stage.brokerSeries.stream().filter(s -> s instanceof Map<?, ?> m && m.containsKey("samplingError")).count());
        var values = stage.coverage.stream().map(Coverage::observedLatencyNanos).sorted().toList();
        for (int percentile : List.of(50, 95, 99)) { result.put("coverageP" + percentile + "Ms", LikePerfLoad.percentile(values, percentile / 100.0)); }
        StringBuilder csv = new StringBuilder("userIndex,articleIndex,version,observedVersion,observedLatencyNanos\n");
        for (var sample : stage.coverage) { csv.append(sample.user()).append(',').append(sample.article()).append(',').append(sample.version()).append(',').append(sample.observedVersion()).append(',').append(sample.observedLatencyNanos()).append('\n'); }
        Files.writeString(output.resolve(stage.name + "-coverage.csv"), csv.toString());
        Files.writeString(output.resolve(stage.name + "-stage.json"), fixture.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
    @Override public void close() { sampler.shutdownNow(); try { sampler.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } broker.close(); }
    record Pending(int user, int article, long version) { }
    record Relation(int user, int article) { }
    record Target(long version, boolean liked) { }
    record Coverage(int user, int article, long version, long observedVersion, long observedLatencyNanos) { }
    static final class Stage {
        final String name;
        final List<Map<String, Object>> pendingSeries = Collections.synchronizedList(new ArrayList<>());
        final List<Object> brokerSeries = Collections.synchronizedList(new ArrayList<>());
        final ConcurrentLinkedQueue<Coverage> coverage = new ConcurrentLinkedQueue<>();
        Stage(String name) { this.name = name; }
    }
}
