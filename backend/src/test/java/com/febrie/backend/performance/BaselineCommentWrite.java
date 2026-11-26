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

/** 将每笔已发布请求与幂等结果、一级评论、文章计数及 ES 可见文档逐一关联。 */
final class BaselineCommentWrite {
    record Audit(long confirmed, long persisted, long unexpectedResults, long untrackedRoots, long wrongCounts,
                 BaselineCommentFixture.IndexAudit index) {
        boolean sqlMatches() { return persisted == confirmed && unexpectedResults == 0 && untrackedRoots == 0 && wrongCounts == 0; }
        boolean passed() {
            return sqlMatches() && index.missingOrWrong() == 0 && index.visibleTotal() == confirmed;
        }
    }

    record Result(Audit audit, long indexOutboxPeak, long oldestIndexOutboxMs, long countOutboxPeak, long deadPeak,
                  long mqPeak, boolean growing, Long drainMs, Long reconciledMs, List<BaselineProbe.Queue> endQueues) { }

    record Stored(String submission, long article, long actor, Long id, String error,
                  long commentArticle, long author, long root, boolean deleted, String content) {
        boolean matches(BaselineJournal.Event expected) {
            return expected != null && article == expected.article() && actor == expected.user() && error == null
                    && id != null && id.toString().equals(expected.commentId()) && commentArticle == expected.article()
                    && author == expected.user() && root == 0 && !deleted && ("基线评论 " + submission).equals(content);
        }
    }

    private final BaselineEnvironment.Site site;
    private final Map<String, BaselineJournal.Event> confirmed = new HashMap<>();

    BaselineCommentWrite(BaselineEnvironment.Site site) { this.site = site; }

    void append(List<BaselineJournal.Event> events, int offset) {
        for (var event : events.subList(offset, events.size())) {
            if (!event.commentSubmit() || !event.ok()) { continue; }
            int first = site.commentWriteFirst();
            if (event.article() < first || event.article() >= first + BaselineEnvironment.COMMENT_WRITE_ARTICLES) {
                throw new IllegalStateException("评论发压目标超出写档文章范围");
            }
            if (event.commentId() == null || event.submission() == null
                    || confirmed.putIfAbsent(event.submission(), event) != null) {
                throw new IllegalStateException("评论发布记录缺少评论 ID 或唯一提交 ID");
            }
        }
    }

    Result drain(long stopped, BaselineProbe.CommentWork initial, List<BaselineProbe.Sample> samples,
                 Callable<BaselineProbe.Sample> probe) throws Exception {
        Audit audit = null;
        Long drainedMs = null;
        Long reconciledMs = null;
        long deadline = stopped + 120_000;
        List<BaselineProbe.Queue> latestQueues = List.of();
        do {
            var sample = probe.call();
            var comment = sample.comment();
            if (comment != null) {
                latestQueues = comment.mq().partitions();
                if (comment.mq().dead() > 0) { break; }
                // 同时核对两条链路的 ACK，避免用管理接口暂未刷新的空队列提前判定排空。
                if (drained(initial, comment)) {
                    if (drainedMs == null) { drainedMs = Math.max(0, sample.at() - stopped); }
                    audit = audit();
                    if (!audit.sqlMatches()) { break; }
                    if (audit.passed()) {
                        reconciledMs = Math.max(0, System.currentTimeMillis() - stopped);
                        break;
                    }
                }
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { break; }
            Thread.sleep(Math.min(2_000, remaining));
        } while (true);
        if (audit == null) { audit = audit(); }
        var observed = samples.stream().map(BaselineProbe.Sample::comment).filter(Objects::nonNull).toList();
        var measured = samples.stream().filter(sample -> sample.phase().equals("measure"))
                .map(BaselineProbe.Sample::comment).filter(Objects::nonNull).toList();
        return new Result(audit,
                observed.stream().mapToLong(BaselineProbe.CommentWork::indexOutbox).max().orElse(0),
                observed.stream().mapToLong(BaselineProbe.CommentWork::oldestIndexOutboxMs).max().orElse(0),
                observed.stream().mapToLong(BaselineProbe.CommentWork::countOutbox).max().orElse(0),
                observed.stream().mapToLong(value -> value.mq().dead()).max().orElse(0),
                observed.stream().mapToLong(value -> value.mq().pending()).max().orElse(0),
                backlogGrowing(measured), drainedMs, reconciledMs, latestQueues);
    }

    private boolean drained(BaselineProbe.CommentWork first, BaselineProbe.CommentWork current) {
        long indexAck = current.mq().partitions().getLast().ack() - first.mq().partitions().getLast().ack();
        long countAck = current.mq().ack() - first.mq().ack() - indexAck;
        return current.indexOutbox() == 0 && current.countOutbox() == 0 && current.mq().pending() == 0
                && countAck >= confirmed.size() && indexAck >= confirmed.size();
    }

    private static boolean backlogGrowing(List<BaselineProbe.CommentWork> measured) {
        if (measured.isEmpty()) { return false; }
        if (BaselineStats.growing(measured.stream().map(BaselineProbe.CommentWork::indexOutbox).toList())
                || BaselineStats.growing(measured.stream().map(BaselineProbe.CommentWork::countOutbox).toList())
                || BaselineStats.growing(measured.stream().map(value -> value.oldestIndexOutboxMs() / 1000).toList())) {
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
        confirmed.values().forEach(event -> expectedCounts.merge(event.article(), 1L, Long::sum));
        long unexpected = 0;
        for (var row : stored) {
            if (row.id() != null && row.id() > site.commentSeedMax() && row.commentArticle() > 0 && row.root() == 0) {
                trackedRoots.add(row.id());
            }
            var expected = confirmed.get(row.submission());
            if (!row.matches(expected) || indexed.putIfAbsent(row.id(), row.article()) != null) {
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
        return new Audit(confirmed.size(), indexed.size(), unexpected, roots - trackedRoots.size(), wrongCounts, es);
    }
}
