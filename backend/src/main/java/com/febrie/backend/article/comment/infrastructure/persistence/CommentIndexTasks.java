package com.febrie.backend.article.comment.infrastructure.persistence;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** SQL 层提供两种任务的短租约以及一致的一级评论投影。 */
@Repository
public class CommentIndexTasks {
    private static final String PROJECTION = """
            SELECT c.id, c.article_id, c.created_at, c.deleted, c.index_version, l.like_count
            FROM article_comment c JOIN comment_like_count l ON l.comment_id = c.id
            WHERE c.root_id = 0
            """;
    private static final RowMapper<Document> DOCUMENT = (rs, index) -> new Document(
            rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli(),
            !rs.getBoolean(4), rs.getLong(5), rs.getLong(6));

    private final JdbcTemplate sql;

    public CommentIndexTasks(JdbcTemplate sql) { this.sql = sql; }

    /** 点赞与删除共用评论行锁，调用方持有同一个 SQL 事务。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActiveRoot(long id) {
        return sql.query("SELECT deleted FROM article_comment WHERE id = ? AND root_id = 0 FOR UPDATE",
                (rs, index) -> !rs.getBoolean(1), id).stream().findFirst().orElse(false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void scheduleLike(long id) {
        if (sql.update("UPDATE article_comment SET index_version = index_version + 1 WHERE id = ?", id) != 1) {
            throw new IllegalStateException("评论点赞索引版本更新失败");
        }
        long version = sql.queryForObject("SELECT index_version FROM article_comment WHERE id = ?", Long.class, id);
        // 已有任务只改版本，持续变化不会推迟最初约定的到期时间。
        sql.update("""
                INSERT INTO comment_like_index_outbox(comment_id, index_version, available_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(3) + INTERVAL 5 SECOND) AS incoming
                ON DUPLICATE KEY UPDATE index_version = incoming.index_version
                """, id, version);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Claim claimLifecycle() {
        var events = sql.query("""
                SELECT id, comment_id, index_version FROM comment_index_outbox
                WHERE available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY available_at, id LIMIT 20 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Event(rs.getLong(1), rs.getLong(2), rs.getLong(3)));
        var token = UUID.randomUUID().toString();
        for (var event : events) {
            sql.update("""
                    UPDATE comment_index_outbox SET claim_token = ?, available_at = CURRENT_TIMESTAMP(3) + INTERVAL 30 SECOND
                    WHERE id = ?
                    """, token, event.id());
        }
        return new Claim(token, events);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Claim claimLikes() {
        var events = sql.query("""
                SELECT comment_id, index_version FROM comment_like_index_outbox
                WHERE available_at <= CURRENT_TIMESTAMP(3)
                ORDER BY available_at, comment_id LIMIT 100 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new Event(rs.getLong(1), rs.getLong(1), rs.getLong(2)));
        var token = UUID.randomUUID().toString();
        for (var event : events) {
            sql.update("""
                    UPDATE comment_like_index_outbox SET claim_token = ?, available_at = CURRENT_TIMESTAMP(3) + INTERVAL 30 SECOND
                    WHERE comment_id = ?
                    """, token, event.commentId());
        }
        return new Claim(token, events);
    }

    public void finishLifecycle(Event event, String token, boolean success) {
        if (success) {
            sql.update("DELETE FROM comment_index_outbox WHERE id = ? AND claim_token = ?", event.id(), token);
        } else {
            sql.update("""
                    UPDATE comment_index_outbox SET claim_token = NULL, available_at = CURRENT_TIMESTAMP(3) + INTERVAL 5 SECOND
                    WHERE id = ? AND claim_token = ?
                    """, event.id(), token);
        }
    }

    public void finishLike(Event event, String token, boolean success) {
        if (success && sql.update("""
                DELETE FROM comment_like_index_outbox WHERE comment_id = ? AND claim_token = ? AND index_version = ?
                """, event.commentId(), token, event.version()) == 1) return;
        // 期间有新点赞时仅释放本轮租约，保留最新版本供后续批次读取。
        int delay = success ? 0 : 5;
        sql.update("""
                UPDATE comment_like_index_outbox SET claim_token = NULL,
                    available_at = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3))
                WHERE comment_id = ? AND claim_token = ?
                """, delay, event.commentId(), token);
    }

    public List<Document> load(Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        return sql.query(PROJECTION + " AND c.id IN (" + marks + ")", DOCUMENT, ids.toArray());
    }

    public long maxId() {
        return sql.queryForObject("SELECT COALESCE(MAX(id), 0) FROM article_comment WHERE root_id = 0", Long.class);
    }

    public int pendingLifecycle() {
        return sql.queryForObject("SELECT COUNT(*) FROM comment_index_outbox", Integer.class);
    }

    public List<Document> scan(long after, long high) {
        return sql.query(PROJECTION + " AND c.id > ? AND c.id <= ? ORDER BY c.id LIMIT 200", DOCUMENT, after, high);
    }

    public record Event(long id, long commentId, long version) { }
    public record Claim(String token, List<Event> events) { }
    public record Document(long commentId, long articleId, long createdAt, boolean visible, long indexVersion, long likeCount) { }
}
