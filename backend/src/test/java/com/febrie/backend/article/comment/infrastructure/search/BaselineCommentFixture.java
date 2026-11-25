package com.febrie.backend.article.comment.infrastructure.search;

import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅用于 k6 专用库，复用评论生产索引映射和队列命名。 */
public final class BaselineCommentFixture {
    public static final int ROOTS_PER_ARTICLE = 32;
    private static final int REPLIES_PER_ROOT = 3;
    private static final LocalDateTime EPOCH = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final int BATCH = 200;

    private BaselineCommentFixture() { }

    private record Seed(long id, long article, long root, long author) { }

    public static void initializeIndex(ConfigurableApplicationContext app) throws IOException {
        app.getBean(CommentIndex.class).initialize();
    }

    public static int seed(ConfigurableApplicationContext app, int articles) throws IOException {
        initializeIndex(app);
        var sql = app.getBean(JdbcTemplate.class);
        var roots = new ArrayList<Seed>();
        var replies = new ArrayList<Seed>();
        long id = 0;
        // 一级 ID 先分配完，清理写档时精确保留全部读种子（含回复）。
        for (long article = 1; article <= articles; article++) {
            for (int i = 0; i < ROOTS_PER_ARTICLE; i++) {
                roots.add(new Seed(++id, article, 0, 1 + i % 8));
            }
        }
        for (var root : roots) {
            for (int i = 0; i < REPLIES_PER_ROOT; i++) {
                replies.add(new Seed(++id, root.article(), root.id(), 1 + i));
            }
        }
        insert(sql, roots);
        insert(sql, replies);
        sql.batchUpdate("INSERT INTO comment_like_count(comment_id, like_count) VALUES (?, ?)",
                roots, BATCH, (ps, root) -> { ps.setLong(1, root.id()); ps.setInt(2, likes(root.id())); });
        sql.execute("""
                INSERT INTO comment_like_state(user_id, comment_id, liked)
                SELECT u.id, c.comment_id, TRUE FROM comment_like_count c JOIN users u ON u.id <= c.like_count
                """);
        sql.update("UPDATE article SET comment_count=? WHERE id BETWEEN 1 AND ?", ROOTS_PER_ARTICLE, articles);
        var index = app.getBean(CommentIndex.class);
        var tasks = app.getBean(CommentIndexTasks.class);
        long after = 0;
        int indexed = 0;
        while (after < roots.size()) {
            var batch = tasks.scan(after, roots.size());
            if (batch.isEmpty() || index.write(batch).size() != batch.size() || index.differences(batch) != 0) {
                throw new IllegalStateException("评论读种子与 ES 索引不一致");
            }
            after = batch.getLast().commentId();
            indexed += batch.size();
        }
        if (indexed != roots.size()) { throw new IllegalStateException("一级评论索引种子缺行"); }
        index.refresh();
        return Math.toIntExact(id);
    }

    private static int likes(long id) { return (int) (id % 5); }

    private static void insert(JdbcTemplate sql, List<Seed> rows) {
        sql.batchUpdate("""
                INSERT INTO article_comment(id, article_id, root_id, reply_to_id, author_id, content, created_at)
                VALUES (?, ?, ?, NULLIF(?, 0), ?, ?, ?)
                """, rows, BATCH, (ps, row) -> {
            ps.setLong(1, row.id()); ps.setLong(2, row.article());
            ps.setLong(3, row.root()); ps.setLong(4, row.root()); ps.setLong(5, row.author());
            ps.setString(6, row.root() == 0 ? "基线一级评论" : "基线回复");
            ps.setObject(7, EPOCH.plusSeconds(row.id()));
        });
    }

    public record IndexAudit(long missingOrWrong, long visibleTotal) { }

    public static IndexAudit verifyVisible(ConfigurableApplicationContext app, String name, Map<Long, Long> comments)
            throws IOException {
        var es = app.getBean(ElasticsearchClient.class);
        var ids = new ArrayList<>(comments.keySet());
        long matched = 0;
        // 使用普通搜索观察自然 refresh，实时 GET 会提前读到尚未对列表可见的文档。
        for (int i = 0; i < ids.size(); i += BATCH) {
            var batch = ids.subList(i, Math.min(i + BATCH, ids.size())).stream().map(String::valueOf).toList();
            var response = es.search(request -> request.index(name + "-read").size(batch.size())
                    .allowPartialSearchResults(false).query(q -> q.ids(query -> query.values(batch))),
                    CommentIndexTasks.Document.class);
            if (response.timedOut() || response.shards().failed().intValue() > 0) {
                throw new IOException("评论索引对账查询不完整");
            }
            var found = new HashSet<Long>();
            for (var hit : response.hits().hits()) {
                var doc = hit.source();
                if (doc != null && doc.visible() && doc.indexVersion() == 1 && doc.likeCount() == 0
                        && hit.id().equals(Long.toString(doc.commentId()))
                        && Long.valueOf(doc.articleId()).equals(comments.get(doc.commentId()))) {
                    found.add(doc.commentId());
                }
            }
            matched += found.size();
        }
        var count = es.count(request -> request.index(name + "-read").query(q -> q.term(t -> t.field("visible").value(true))));
        if (count.shards().failed().intValue() > 0) { throw new IOException("评论索引计数查询不完整"); }
        return new IndexAudit(ids.size() - matched, count.count());
    }

    public static void resetWrites(ConfigurableApplicationContext app, long seedMax, int first, int last) {
        var sql = app.getBean(JdbcTemplate.class);
        new TransactionTemplate(app.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            // 专用库中只有发压新增行超过种子边界。清空上档的幂等结果与索引生命周期任务。
            sql.update("DELETE FROM comment_index_outbox WHERE comment_id>?", seedMax);
            sql.update("DELETE FROM comment_like_index_outbox WHERE comment_id>?", seedMax);
            sql.update("DELETE FROM comment_write_result WHERE article_id BETWEEN ? AND ?", first, last);
            sql.update("DELETE FROM comment_like_state WHERE comment_id>?", seedMax);
            sql.update("DELETE FROM comment_like_count WHERE comment_id>?", seedMax);
            sql.update("DELETE FROM article_comment WHERE id>?", seedMax);
            sql.update("UPDATE article SET comment_count=0 WHERE id BETWEEN ? AND ? AND comment_count<>0", first, last);
        });
    }

    public static List<String> writeQueues(String prefix) {
        return LongStream.range(0, CommentBroker.PARTITIONS).mapToObj(i -> prefix + ".comment.write." + i + ".q").toList();
    }

    public static String indexQueue(String prefix) { return prefix + ".comment.index.q"; }

    public static List<String> deadQueues(String prefix) {
        return List.of(prefix + ".comment.write.dead.q", prefix + ".comment.index.dead.q");
    }

    public static void cleanupQueues(ConfigurableApplicationContext app, String prefix) {
        app.getBean("commentIndexScheduler", ThreadPoolTaskScheduler.class).shutdown();
        app.getBean("commentWriteListeners", SmartLifecycle.class).stop();
        app.getBean("commentIndexListener", SimpleMessageListenerContainer.class).stop();
        app.getBean("commentRefreshListener", SimpleMessageListenerContainer.class).stop();
        var admin = app.getBean(AmqpAdmin.class);
        writeQueues(prefix).forEach(admin::deleteQueue);
        admin.deleteQueue(indexQueue(prefix));
        deadQueues(prefix).forEach(admin::deleteQueue);
        admin.deleteQueue(prefix + ".comment.refresh.q");
    }
}
