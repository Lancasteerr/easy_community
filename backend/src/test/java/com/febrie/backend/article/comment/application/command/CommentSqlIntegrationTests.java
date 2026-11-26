package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCountOutbox;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.infrastructure.redis.CommentMaintenance;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserBasicQueryService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅启动 MySQL，验证真实 SQL 和 Spring 事务代理，不加载 Web、Redis 或 RabbitMQ。 */
@EnabledIfEnvironmentVariable(named = "IT_COMMENT_SQL", matches = "true")
class CommentSqlIntegrationTests {
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate sql;

    @BeforeAll
    static void setup() {
        String url = System.getenv("IT_DB_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/easy_community_comment_sql_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("仅允许专用评论 SQL 测试库");
        }
        var source = new DriverManagerDataSource(url, System.getenv("IT_DB_USERNAME"), System.getenv("IT_DB_PASSWORD"));
        Flyway.configure().dataSource(source).load().migrate();
        sql = new JdbcTemplate(source);
        var actualViews = new CommentViews(mock(UserBasicQueryService.class), mock(LikeQueryService.class), null, null);
        var views = spy(actualViews);
        doAnswer(call -> actualViews.submitted(call.getArgument(0), "作者"))
                .when(views).published(any(CommentRow.class), anyLong());
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> sql);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean(Clock.class, Clock::systemUTC);
        context.registerBean(CommentMaintenance.class, () -> mock(CommentMaintenance.class));
        context.registerBean(CommentViews.class, () -> views);
        context.register(Transactions.class, CommentStore.class, CommentCommandResults.class, CommentCountOutbox.class,
                CommentIndexTasks.class, CommentCountProjection.class, CommentWrites.class, CommentSubmissionTransactions.class,
                CommentSubmissions.class, CommentWriteService.class);
        context.refresh();
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @Test
    void eitherOutboxFailureRollsBackAndLeavesTheSameIdReusable() {
        var fixture = seed();
        for (String table : List.of("comment_index_outbox", "comment_count_outbox")) {
            var before = totals();
            String id = UUID.randomUUID().toString();
            // 用数据库错误打断写入，确认已经执行的 SQL 也随事务回滚。
            sql.execute("CREATE TRIGGER reject_comment_event BEFORE INSERT ON " + table
                    + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='outbox test failure'");
            try {
                assertThrows(DataAccessException.class, () -> publish(fixture, id, "回滚后重试", null));
                assertEquals(before, totals());
            } finally {
                sql.execute("DROP TRIGGER reject_comment_event");
            }
            assertNotNull(publish(fixture, id, "回滚后重试", null).comment());
            assertEquals(before.stream().map(n -> n + 1).toList(), totals());
        }
    }

    @Test
    void concurrentPutsShareOneCommentAndOneEventPerOutbox() throws Exception {
        var fixture = seed();
        var before = totals();
        var ready = new CountDownLatch(1);
        String id = UUID.randomUUID().toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<CommentSubmissions.View> task = () -> {
                ready.await();
                return publish(fixture, id, " 并发发布 ", null);
            };
            var one = executor.submit(task);
            var two = executor.submit(task);
            ready.countDown();
            assertEquals(one.get(10, TimeUnit.SECONDS).comment(), two.get(10, TimeUnit.SECONDS).comment());
        }
        assertEquals(before.stream().map(n -> n + 1).toList(), totals());
        assertEquals(409, assertThrows(ApiException.class, () -> publish(fixture, id, "并发发布", null)).status().value());
    }

    @Test
    void recountIgnoresRepliesAndResetsAfterOnlyRootIsDeleted() {
        var fixture = seed();
        var before = totals();
        var root = publish(fixture, UUID.randomUUID().toString(), "一级评论", null).comment();
        long rootId = Long.parseLong(root.id());
        var reply = publish(fixture, UUID.randomUUID().toString(), "回复", rootId).comment();
        assertEquals(root.id(), reply.rootId());
        assertEquals(before.get(3) + 1, totals().get(3));
        assertEquals(before.get(4) + 1, totals().get(4));

        var projection = context.getBean(CommentCountProjection.class);
        projection.recount(List.of(fixture.article(), fixture.article()));
        assertEquals(1, count(fixture));
        var deletions = context.getBean(CommentWriteService.class);
        String id = UUID.randomUUID().toString();
        deletions.delete(fixture.article(), rootId, fixture.actor(), id);
        deletions.delete(fixture.article(), rootId, fixture.actor(), id);
        deletions.delete(fixture.article(), rootId, fixture.actor(), UUID.randomUUID().toString());
        assertEquals(before.get(3) + 2, totals().get(3));
        assertEquals(before.get(4) + 2, totals().get(4));
        projection.recount(List.of(fixture.article()));
        assertEquals(0, count(fixture));

        var counts = context.getBean(CommentCountOutbox.class);
        var batch = counts.claim(128);
        var events = batch.events().stream().filter(event -> event.articleId() == fixture.article()).toList();
        assertEquals(2, events.size());
        counts.finish(batch, Set.of(events.getFirst().id()));
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM comment_count_outbox WHERE article_id=? AND claim_token IS NULL",
                Long.class, fixture.article()));

        var index = context.getBean(CommentIndexTasks.class);
        var indexBatch = index.claimLifecycle(200);
        var indexEvents = indexBatch.events().stream().filter(event -> event.commentId() == rootId).toList();
        assertEquals(List.of(1L, 2L), indexEvents.stream().map(CommentIndexTasks.Event::version).toList());
        index.finishLifecycle(indexBatch, Set.of(indexEvents.getFirst().id()));
        assertEquals(1, sql.queryForObject("SELECT COUNT(*) FROM comment_index_outbox WHERE comment_id=? AND claim_token IS NULL",
                Long.class, rootId));
    }

    private static CommentSubmissions.View publish(Fixture fixture, String id, String content, Long target) {
        return context.getBean(CommentSubmissions.class)
                .submit(fixture.article(), fixture.actor(), "作者", id, new CommentInput(content, target));
    }

    private static long count(Fixture fixture) {
        return sql.queryForObject("SELECT comment_count FROM article WHERE id=?", Long.class, fixture.article());
    }

    private static List<Long> totals() {
        return List.of("article_comment", "comment_write_result", "comment_like_count",
                        "comment_index_outbox", "comment_count_outbox").stream()
                .map(table -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).toList();
    }

    private static Fixture seed() {
        String username = "sql_" + UUID.randomUUID().toString().substring(0, 12);
        sql.update("INSERT INTO users(username, role) VALUES (?, 'USER')", username);
        long user = sql.queryForObject("SELECT id FROM users WHERE username=?", Long.class, username);
        sql.update("""
                INSERT INTO article(author_id,title,summary,content_json,content_html,tag_ids,created_at,updated_at,published_at)
                VALUES (?, '事务测试', '摘要', JSON_OBJECT(), '', JSON_ARRAY(), NOW(3), NOW(3), NOW(3))
                """, user);
        long article = sql.queryForObject("SELECT id FROM article WHERE author_id=?", Long.class, user);
        return new Fixture(article, new UserActor(user, Role.USER, 1));
    }

    private record Fixture(long article, UserActor actor) { }

    @EnableTransactionManagement
    static class Transactions { }
}
