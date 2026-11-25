package com.febrie.backend.performance;

import com.febrie.backend.article.like.infrastructure.messaging.LikeListenerGroup;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单档串联相同时间轴上的预热、正式发压、自然刷盘和异步排空。 */
final class BaselineStage {
    private final BaselineEnvironment.Site site;
    private final BaselineK6.Stage stage;
    private final BaselineReconcile reconcile;
    private final BaselineCommentWrite commentWrite;
    private final BaselineCommentReplyWrite replyWrite;
    private final List<BaselineProbe.Sample> samples = new ArrayList<>();
    private final List<String> issues = new ArrayList<>();
    private final Map<String, Object> diagnostics = new LinkedHashMap<>();

    private BaselineStage(BaselineEnvironment.Site site, BaselineK6.Stage stage) {
        this.site = site; this.stage = stage;
        reconcile = new BaselineReconcile(site.jdbc);
        commentWrite = stage.writesComments() ? new BaselineCommentWrite(site) : null;
        replyWrite = stage.writesReplies() ? new BaselineCommentReplyWrite(site) : null;
    }

    static BaselineReport.Stage run(int round, BaselineK6.Stage stage, BaselineEnvironment.Site site,
                                    Path script, Path input, Path output, boolean smoke) throws Exception {
        return new BaselineStage(site, stage).measure(round, script, input, output, smoke);
    }

    private BaselineReport.Stage measure(int round, Path script, Path input, Path output, boolean smoke) throws Exception {
        boolean recoveryProbe = stage.scenario().equals("like-probe");
        boolean cold = stage.scenario().equals("detail-cold");
        diagnostics.put("authenticatedRequestsPerMinute", stage.userLimit());
        diagnostics.put("likesPerMinute", stage.likeLimit());
        try (var instrumentation = BaselineInstrumentation.attach(site);
             var probe = new BaselineProbe(site, reconcile, output.resolve("telemetry.jsonl"))) {
            var ready = awaitProbe(probe);
            if (smoke && stage.scenario().equals("search")) {
                diagnostics.put("lockProbe", BaselineFailureProbe.verify(site, probe));
            }
            if (!cold && !recoveryProbe) { phase(probe, script, input, output, "warm", smoke ? 4 : 60); }
            var first = probe.sample("measure-start", stage.readsDetails());
            checkSample(first);
            samples.add(first);
            int seconds = smoke ? 8 : cold ? 60 : 300;
            List<BaselineJournal.Event> events;
            var group = site.app.getBean("likeListener", LikeListenerGroup.class);
            if (recoveryProbe) { group.stop(); }
            try {
                events = phase(probe, script, input, output, "measure", seconds);
                if (recoveryProbe) {
                    long until = System.currentTimeMillis() + 8000;
                    while (peakPending() == 0 && System.currentTimeMillis() < until) {
                        collect(probe, "probe-backlog");
                        Thread.sleep(500);
                    }
                }
            } finally { if (recoveryProbe) { group.start(); } }
            var end = probe.sample("measure-end", stage.readsDetails());
            checkSample(end);
            samples.add(end);
            var primary = stage.writesComments() ? events.stream().filter(event -> !event.auxiliary()).toList() : events;
            long stopped = primary.stream().mapToLong(BaselineJournal.Event::end).max().orElse(System.currentTimeMillis());
            var drain = drain(probe, stopped, ready.mq().ack());
            var k6 = BaselineStats.k6(output.resolve("measure-metrics.json.gz"), site.json);
            double window = seconds;
            if (!primary.isEmpty()) {
                long firstRequest = primary.stream().mapToLong(BaselineJournal.Event::start).min().orElseThrow();
                window = Math.max(seconds, (stopped - firstRequest) / 1000.0);
            }
            var routes = BaselineStats.routes(events, window);
            diagnostics.put("workloadVariants", events.stream().filter(event -> event.variant() != null && !event.variant().isBlank())
                    .collect(java.util.stream.Collectors.groupingBy(BaselineJournal.Event::variant,
                            java.util.stream.Collectors.counting())));
            long successes = primary.stream().filter(BaselineJournal.Event::ok).count();
            if (primary.isEmpty() || successes * 100 < primary.size() * 99L) { issues.add("业务检查成功率低于 99% 或没有样本"); }
            long planned = (long) seconds * stage.load();
            if (!stage.closed()) {
                // 混合按旅程计数，单接口档仍核对一迭代一条主请求。
                long started = stage.mixed() ? k6.startedJourneys() : primary.size();
                if (k6.dropped() * 100 > planned || started != k6.iterations() || started < planned * .99) {
                    issues.add("未达到计划到达率或主负载与 k6 迭代数不符");
                }
            }
            if (stage.comments() && (k6.dropped() > 0 || successes / window < stage.load() * .99)) {
                issues.add("评论主请求成功吞吐低于目标的 99% 或存在丢弃迭代");
            }
            if (stage.mixed()) {
                diagnostics.put("journeyStartsPerSecond", k6.startedJourneys() / (double) seconds);
                diagnostics.put("httpRequestsPerSecond", events.size() / window);
                if (k6.startedJourneys() == 0 || 100 * k6.successfulJourneys() < 99 * k6.startedJourneys()) {
                    issues.add("完整旅程通过率低于 99%");
                }
            }
            if (hostSaturated()) { issues.add("宿主机 CPU 连续三次采样超过 90%，发压与依赖资源可能争用"); }
            var depth = samples.stream().filter(sample -> sample.phase().equals("measure") && sample.mq() != null)
                    .map(sample -> sample.mq().pending()).toList();
            boolean growing = !recoveryProbe && BaselineStats.growing(depth);
            boolean partitionGrowing = !recoveryProbe && partitionGrowing();
            if (growing || partitionGrowing) { issues.add("稳态 MQ 积压持续增长"); }
            if (recoveryProbe && peakPending() == 0) { issues.add("暂停消费后未观察到积压"); }
            int likeOverlap = BaselineStats.likeUsersInFlight(events);
            if (recoveryProbe && stage.closed() && likeOverlap < 2) { issues.add("未观察到不同账号的热点写并发"); }
            Long waits = sqlDelta(first, end, true), waitMs = sqlDelta(first, end, false);
            diagnostics.put("acceptedInMeasure", events.stream().filter(BaselineJournal.Event::accepted).count());
            diagnostics.put("sampledSqlCoverageCount", reconcile.coverage().size());
            diagnostics.put("sampledSqlCoverageP95Ms", BaselineStats.percentile(reconcile.coverage().stream().sorted().toList(), .95));
            diagnostics.put("windowSeconds", window);
            diagnostics.put("plannedIterations", stage.closed() ? null : (long) stage.load() * seconds);
            diagnostics.put("deadLetterPeak", samples.stream().filter(sample -> sample.mq() != null)
                    .mapToLong(sample -> sample.mq().dead()).max().orElse(0));
            diagnostics.put("partitionMq", partitionDeltas(first, end, window));
            long backlog = peakPending();
            if (stage.readsComments() || stage.writesComments()) { reportCommentEs(first, end); }
            if (commentWrite != null) {
                var writeResult = commentWrite.drain(stopped, ready.comment(), events, samples,
                        () -> collect(probe, "comment-drain"));
                diagnostics.put("commentWrite", writeResult);
                backlog = writeResult.mqPeak();
                growing |= writeResult.growing();
                if (writeResult.audit().accepted() == 0) { issues.add("评论写档没有受理样本"); }
                if (writeResult.deadPeak() > 0) { issues.add("评论写入或索引出现死信"); }
                if (writeResult.growing()) { issues.add("评论队列或索引 Outbox 持续积压"); }
                if (!writeResult.audit().passed()) { issues.add("评论受理、落库、文章计数或 ES 搜索对账失败"); }
                if (writeResult.drainMs() == null) { issues.add("评论队列及索引未完成排空与对账"); }
            }
            if (replyWrite != null) {
                var audit = replyWrite.audit();
                diagnostics.put("commentReplyWrite", audit);
                if (!audit.passed()) { issues.add("回复发布确认、落库结果或文章一级评论数对账失败"); }
            }
            return new BaselineReport.Stage(round, stage.scenario(), stage.load(), stage.model().label,
                    issues.isEmpty(), issues.stream().distinct().toList(), routes, k6, stage.vus(), BaselineStats.overlap(events),
                    likeOverlap, backlog, growing || partitionGrowing, waits, waitMs, drain.coverageMs(), drain.mqMs(),
                    drain.like(), drain.pv(), diagnostics);
        }
    }

    private BaselineProbe.Sample awaitProbe(BaselineProbe probe) throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        do {
            var sample = probe.sample("preflight", stage.readsDetails());
            if (sample.missing().isEmpty()) { return sample; }
            if (System.currentTimeMillis() >= deadline) { throw new IllegalStateException("监控预检缺失：" + sample.missing()); }
            Thread.sleep(1000);
        } while (true);
    }

    private List<BaselineJournal.Event> phase(BaselineProbe probe, Path script, Path input, Path output,
                                             String phase, int seconds) throws Exception {
        var journal = new BaselineJournal(output.resolve(phase + "-http.log"), site.json);
        var process = BaselineK6.start(script, input, output, phase, stage, seconds);
        int offset = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds + 25L).toNanos();
        try {
            while (process.isAlive() && System.nanoTime() < deadline) {
                journal.refresh();
                append(journal.events(), offset);
                offset = journal.events().size();
                collect(probe, phase);
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            BaselineK6.verifyExit(process, 0);
            journal.refresh();
            append(journal.events(), offset);
            if (journal.events().isEmpty()) { throw new IllegalStateException("k6 未输出 HTTP 样本：" + phase); }
            return journal.events();
        } finally { if (process.isAlive()) { process.destroyForcibly().waitFor(); } }
    }

    private void append(List<BaselineJournal.Event> events, int offset) {
        reconcile.append(events, offset);
        if (commentWrite != null) { commentWrite.append(events, offset); }
        if (replyWrite != null) { replyWrite.append(events, offset); }
    }

    private void reportCommentEs(BaselineProbe.Sample first, BaselineProbe.Sample end) {
        var es = new LinkedHashMap<String, Object>();
        for (String key : List.of("openContexts", "heapUsedBytes")) {
            es.put(key + "Peak", samples.stream().filter(sample -> sample.es() != null)
                    .mapToLong(sample -> ((Number) sample.es().get(key)).longValue()).max().orElse(0));
        }
        if (first.es() != null && end.es() != null) {
            es.put("searchRejected", BaselineStats.delta(((Number) first.es().get("rejected")).longValue(),
                    ((Number) end.es().get("rejected")).longValue()));
        }
        diagnostics.put("commentEs", es);
    }

    private BaselineProbe.Sample collect(BaselineProbe probe, String phase) throws Exception {
        var sample = probe.sample(phase, stage.readsDetails());
        checkSample(sample);
        samples.add(sample);
        return sample;
    }

    private void checkSample(BaselineProbe.Sample sample) {
        if (!sample.missing().isEmpty()) { issues.add("采集缺失 " + sample.phase() + " " + sample.missing()); }
    }

    private record Drain(Long coverageMs, Long mqMs, BaselineReconcile.Audit like, BaselineReconcile.Pv pv) { }

    private Drain drain(BaselineProbe probe, long stopped, long ackBefore) throws Exception {
        Long mqAt = null, coverageAt = null;
        BaselineReconcile.Audit like = null;
        BaselineReconcile.Pv pv = null;
        long sqlAtStop = stage.readsDetails() ? reconcile.persistedViews() : 0;
        boolean expectsWrites = stage.writes() && reconcile.accepted() > 0;
        boolean expectsPv = stage.readsDetails() && reconcile.views() > 0;
        long nextAudit = 0;
        do {
            var sample = collect(probe, "drain");
            long elapsed = Math.max(0, sample.at() - stopped);
            var mq = sample.mq();
            if (mq != null && mq.dead() > 0) { issues.add("出现点赞死信"); }
            if (expectsWrites && mq != null && mq.pending() == 0 && mq.ack() - ackBefore >= reconcile.accepted() && mqAt == null) {
                mqAt = elapsed;
                nextAudit = 0;
            }
            if (expectsWrites && coverageAt == null && sample.at() >= nextAudit
                    && (mqAt != null || sample.coverage() != null && "0".equals(sample.coverage().get("pending").toString()))) {
                like = reconcile.audit();
                if (like.passed()) { coverageAt = Math.max(elapsed, System.currentTimeMillis() - stopped); }
                nextAudit = sample.at() + 10000;
            }
            if (expectsPv) { pv = reconcile.pv(sqlAtStop); }
            // 物理 ACK 已确认后，最终对账即使失败也已经能下结论。
            if ((!expectsWrites || mqAt != null && like != null) && (!expectsPv || pv.passed())) { break; }
            if (elapsed >= 120000) { break; }
            Thread.sleep(2000);
        } while (true);
        if (stage.writes()) {
            if (like == null || !like.passed()) { like = reconcile.audit(); }
            if (!like.passed()) { issues.add("点赞最终关系或计数对账失败"); }
            if (expectsWrites && (mqAt == null || coverageAt == null)) { issues.add("点赞排空或覆盖超时"); }
            if (!expectsWrites) { issues.add("点赞场景没有受理样本"); }
        }
        if (stage.readsDetails()) {
            if (pv == null) { pv = reconcile.pv(sqlAtStop); }
            if (!pv.passed() || !expectsPv) { issues.add("自然刷盘后 PV 对账失败或无成功详情"); }
        } else if (stage.scenario().equals("search") && reconcile.persistedViews() != 0) {
            issues.add("搜索产生了文章 PV");
        }
        return new Drain(coverageAt, mqAt, like, pv);
    }

    private long peakPending() {
        return samples.stream().filter(sample -> sample.mq() != null).mapToLong(sample -> sample.mq().pending()).max().orElse(0);
    }

    private boolean hostSaturated() {
        int busy = 0;
        for (var sample : samples) {
            if (!sample.phase().equals("measure")) { continue; }
            busy = sample.hostCpu() > .9 ? busy + 1 : 0;
            if (busy >= 3) { return true; }
        }
        return false;
    }

    private boolean partitionGrowing() {
        var measured = samples.stream().filter(sample -> sample.phase().equals("measure") && sample.mq() != null).toList();
        for (int i = 0; i < BaselineData.PARTITIONS; i++) {
            final int partition = i;
            var depth = measured.stream().map(sample -> sample.mq().partitions().get(partition))
                    .map(queue -> queue.ready() + queue.unacked()).toList();
            if (BaselineStats.growing(depth)) { return true; }
        }
        return false;
    }

    private Long sqlDelta(BaselineProbe.Sample first, BaselineProbe.Sample end, boolean count) {
        if (first.mysql() == null || end.mysql() == null) { return null; }
        Long delta = count ? BaselineStats.delta(first.mysql().waits(), end.mysql().waits())
                : BaselineStats.delta(first.mysql().waitMs(), end.mysql().waitMs());
        if (delta == null) { issues.add("MySQL 行锁累计指标发生重置"); }
        return delta;
    }

    private List<Map<String, Object>> partitionDeltas(BaselineProbe.Sample first, BaselineProbe.Sample end, double seconds) {
        if (first.mq() == null || end.mq() == null) { return List.of(); }
        var results = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < first.mq().partitions().size(); i++) {
            var a = first.mq().partitions().get(i);
            var b = end.mq().partitions().get(i);
            Long publish = BaselineStats.delta(a.published(), b.published());
            Long ack = BaselineStats.delta(a.ack(), b.ack());
            Long redelivery = BaselineStats.delta(a.redeliver(), b.redeliver());
            var result = new LinkedHashMap<String, Object>();
            result.put("queue", a.name()); result.put("endReady", b.ready()); result.put("endUnacked", b.unacked());
            final int partition = i;
            result.put("backlogPeak", samples.stream().filter(sample -> sample.mq() != null)
                    .map(sample -> sample.mq().partitions().get(partition)).mapToLong(q -> q.ready() + q.unacked())
                    .max().orElse(0));
            result.put("publishPerSecond", publish == null ? null : publish / seconds);
            result.put("ackPerSecond", ack == null ? null : ack / seconds); result.put("redelivered", redelivery);
            if (publish == null || ack == null || redelivery == null) { issues.add("MQ 累计指标发生重置：" + a.name()); }
            results.add(result);
        }
        return results;
    }
}
