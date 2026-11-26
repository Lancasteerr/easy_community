package com.febrie.backend.article.comment.infrastructure.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 评论事实与计数事件一起提交，发布者在事务外使用短租约投递。 */
@Repository
public class CommentCountOutbox {
    private final JdbcTemplate sql;

    public CommentCountOutbox(JdbcTemplate sql) { this.sql = sql; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(long articleId) {
        sql.update("INSERT INTO comment_count_outbox(article_id) VALUES (?)", articleId);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Claim claim(int limit) {
        var events = sql.query("""
                SELECT id, article_id FROM comment_count_outbox WHERE available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY available_at, id LIMIT ? FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Event(rs.getLong(1), rs.getLong(2)), limit);
        String token = UUID.randomUUID().toString();
        if (!events.isEmpty()) {
            var args = new ArrayList<Object>();
            args.add(token);
            events.forEach(event -> args.add(event.id()));
            sql.update("""
                    UPDATE comment_count_outbox SET claim_token = ?, available_at = CURRENT_TIMESTAMP(3) + INTERVAL 30 SECOND
                    WHERE id IN (%s)
                    """.formatted(marks(events.size())), args.toArray());
        }
        return new Claim(token, events);
    }

    public void finish(Claim claim, Set<Long> confirmed) {
        var sent = new ArrayList<Object>();
        var retry = new ArrayList<Object>();
        sent.add(claim.token());
        retry.add(claim.token());
        for (var event : claim.events()) {
            (confirmed.contains(event.id()) ? sent : retry).add(event.id());
        }
        // 只处理本轮租约。未确认的事件继续留在 SQL，稍后重试。
        if (sent.size() > 1) sql.update("DELETE FROM comment_count_outbox WHERE claim_token = ? AND id IN ("
                + marks(sent.size() - 1) + ")", sent.toArray());
        if (retry.size() > 1) sql.update("""
                UPDATE comment_count_outbox SET claim_token = NULL, available_at = CURRENT_TIMESTAMP(3) + INTERVAL 5 SECOND
                WHERE claim_token = ? AND id IN (%s)
                """.formatted(marks(retry.size() - 1)), retry.toArray());
    }

    private static String marks(int size) { return String.join(",", Collections.nCopies(size, "?")); }

    public record Event(long id, long articleId) { }
    public record Claim(String token, List<Event> events) { }
}
