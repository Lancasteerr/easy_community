package com.febrie.backend.performance;

import com.febrie.backend.article.comment.infrastructure.search.BaselineCommentFixture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/** 将每笔已受理请求与幂等结果、一级评论、文章计数及 ES 可见文档逐一关联。 */
final class BaselineCommentWrite {
    record Audit(long accepted, long persisted, long unexpectedResults, long untrackedRoots, long wrongCounts,
                 BaselineCommentFixture.IndexAudit index) {
        boolean sqlMatches() { return persisted == accepted && unexpectedResults == 0 && untrackedRoots == 0 && wrongCounts == 0; }
        boolean passed() {
            return sqlMatches() && index.missingOrWrong() == 0 && index.visibleTotal() == accepted;
        }
    }

    record Observation(long samples, int published, int visible, long publishedP95Ms, long visibleP95Ms) { }
    record Result(Audit audit, Observation observation, long outboxPeak, long oldestOutboxMs, long deadPeak,
                  long mqPeak, boolean growing, Long drainMs, List<BaselineProbe.Queue> endQueues) { }

    private record Stored(String submission, long article, long actor, Long id, String error,
                          long commentArticle, long author, long root, boolean deleted, String content) { }

    private final BaselineEnvironment.Site site;
    private final Map<String, BaselineJournal.Event> accepted = new HashMap<>();

    BaselineCommentWrite(BaselineEnvironment.Site site) { this.site = site; }

    void append(List<BaselineJournal.Event> events, int offset) {
        for (var event : events.subList(offset, events.size())) {
            if (!event.commentSubmit() || !event.ok()) { continue; }
            int first = site.commentWriteFirst();
            if (event.article() < first || event.article() >= first + BaselineEnvironment.COMMENT_WRITE_ARTICLES) {
                throw new IllegalStateException("评论发压目标超出写档文章范围");
            }
            if (event.submission() == null || accepted.putIfAbsent(event.submission(), event) != null) {
                throw new IllegalStateException("评论受理记录缺少唯一提交 ID");
            }
        }
    }

    Result drain(long stopped, BaselineProbe.CommentWork initial, List<BaselineJournal.Event> events,
                 List<BaselineProbe.Sample> samples, Callable<BaselineProbe.Sample> probe) throws Exception {
        Audit audit = null;
        Long drainedMs = null;
        List<BaselineProbe.Queue> latestQueues = List.of();
        do {
            var comment = probe.call().comment();
            if (comment != null) {
                latestQueues = comment.mq().partitions();
                // 同时核对两条链路的 ACK，避免用管理接口暂未刷新的空队列提前判定排空。
                if (drained(initial, comment)) {
                    audit = audit();
                    if (!audit.sqlMatches()) { break; }
                    if (audit.passed()) {
                        drainedMs = Math.max(0, System.currentTimeMillis() - stopped);
                        break;
                    }
                }
                if (comment.mq().dead() > 0) { break; }
            }
            if (System.currentTimeMillis() - stopped >= 120000) { break; }
            Thread.sleep(2000);
        } while (true);
        if (audit == null || drainedMs == null) { audit = audit(); }
        var observed = samples.stream().map(BaselineProbe.Sample::comment).filter(Objects::nonNull).toList();
        var measured = samples.stream().filter(sample -> sample.phase().equals("measure"))
                .map(BaselineProbe.Sample::comment).filter(Objects::nonNull).toList();
        return new Result(audit, observation(events),
                observed.stream().mapToLong(BaselineProbe.CommentWork::outbox).max().orElse(0),
                observed.stream().mapToLong(BaselineProbe.CommentWork::oldestOutboxMs).max().orElse(0),
                observed.stream().mapToLong(value -> value.mq().dead()).max().orElse(0),
                observed.stream().mapToLong(value -> value.mq().pending()).max().orElse(0),
                backlogGrowing(measured), drainedMs, latestQueues);
    }

    private boolean drained(BaselineProbe.CommentWork first, BaselineProbe.CommentWork current) {
        long indexAck = current.mq().partitions().getLast().ack() - first.mq().partitions().getLast().ack();
        long writeAck = current.mq().ack() - first.mq().ack() - indexAck;
        return current.outbox() == 0 && current.mq().pending() == 0
                && writeAck >= accepted.size() && indexAck >= accepted.size();
    }

    private static boolean backlogGrowing(List<BaselineProbe.CommentWork> measured) {
        if (measured.isEmpty()) { return false; }
        if (BaselineStats.growing(measured.stream().map(BaselineProbe.CommentWork::outbox).toList())
                || BaselineStats.growing(measured.stream().map(value -> value.oldestOutboxMs() / 1000).toList())) {
            return true;
        }
        for (int i = 0; i < measured.getFirst().mq().partitions().size(); i++) {
            final int partition = i;
            if (BaselineStats.growing(measured.stream().map(value -> value.mq().partitions().get(partition))
                    .map(queue -> queue.ready() + queue.unacked()).toList())) { return true; }
        }
        return false;
    }

    private Audit audit() throws IOException {
        var stored = site.jdbc.query("""
                SELECT r.operation_id, r.article_id, r.actor_id, r.comment_id, r.error_code,
                       c.article_id, c.author_id, c.root_id, c.deleted, c.content
                FROM comment_write_result r LEFT JOIN article_comment c ON c.id=r.comment_id
                """, (rs, row) -> new Stored(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getObject(4, Long.class), rs.getString(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                rs.getBoolean(9), rs.getString(10)));
        var indexed = new HashMap<Long, Long>();
        var trackedRoots = new HashSet<Long>();
        var expectedCounts = new HashMap<Long, Long>();
        accepted.values().forEach(event -> expectedCounts.merge(event.article(), 1L, Long::sum));
        long unexpected = 0;
        for (var row : stored) {
            if (row.id() != null && row.id() > site.commentSeedMax() && row.commentArticle() > 0 && row.root() == 0) {
                trackedRoots.add(row.id());
            }
            var expected = accepted.get(row.submission());
            if (expected == null || row.article() != expected.article() || row.actor() != expected.user()
                    || row.error() != null || row.id() == null || row.commentArticle() != expected.article()
                    || row.author() != expected.user() || row.root() != 0 || row.deleted()
                    || !("基线评论 " + row.submission()).equals(row.content())
                    || indexed.putIfAbsent(row.id(), row.article()) != null) {
                unexpected++;
            }
        }
        // 对比实际行数也能发现绕过幂等结果表写入的记录。
        long roots = site.jdbc.queryForObject("""
                SELECT COUNT(*) FROM article_comment WHERE id>? AND root_id=0
                """, Long.class, site.commentSeedMax());
        long wrongCounts = 0;
        for (var row : site.jdbc.queryForList("""
                SELECT id,comment_count FROM article WHERE id BETWEEN ? AND ?
                """, site.commentWriteFirst(), site.commentWriteFirst() + BaselineEnvironment.COMMENT_WRITE_ARTICLES - 1)) {
            long article = ((Number) row.get("id")).longValue();
            if (((Number) row.get("comment_count")).longValue() != expectedCounts.getOrDefault(article, 0L)) { wrongCounts++; }
        }
        var es = BaselineCommentFixture.verifyVisible(site.app, site.commentIndex, indexed);
        return new Audit(accepted.size(), indexed.size(), unexpected, roots - trackedRoots.size(), wrongCounts, es);
    }

    static Observation observation(List<BaselineJournal.Event> events) {
        var samples = events.stream().filter(event -> event.commentSubmit() && event.ok() && event.sampled())
                .collect(Collectors.toMap(BaselineJournal.Event::submission, BaselineJournal.Event::start));
        var published = new HashMap<String, Long>();
        var visible = new HashMap<String, Long>();
        for (var event : events) {
            if (!event.auxiliary() || !event.ok() || event.submission() == null) { continue; }
            Long started = samples.get(event.submission());
            if (started == null) { continue; }
            if ("PUBLISHED".equals(event.outcome())) { published.merge(event.submission(), event.end() - started, Math::min); }
            if ("VISIBLE".equals(event.outcome())) { visible.merge(event.submission(), event.end() - started, Math::min); }
        }
        return new Observation(samples.size(), published.size(), visible.size(), p95(published), p95(visible));
    }

    private static long p95(Map<String, Long> latencies) {
        var sorted = new ArrayList<>(latencies.values());
        sorted.sort(null);
        return BaselineStats.percentile(sorted, .95);
    }
}
