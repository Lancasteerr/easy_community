package com.febrie.backend.article.comment;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** SQL 中只保存评论事实；展示投影、消息和 Redis 不参与写事务。 */
@Repository
public class CommentStore {
    private static final String COLUMNS = "id, article_id, root_id, reply_to_id, author_id, content, created_at, deleted";
    private static final RowMapper<CommentRow> ROW = (rs, index) -> new CommentRow(rs.getLong("id"),
            rs.getLong("article_id"), rs.getLong("root_id"), rs.getObject("reply_to_id", Long.class),
            rs.getLong("author_id"), rs.getString("content"),
            rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getBoolean("deleted"));
    private final JdbcTemplate sql;

    public CommentStore(JdbcTemplate sql) {
        this.sql = sql;
    }

    boolean lockPublicArticle(long id, boolean exclusive) {
        return sql.query("SELECT hidden FROM article WHERE id = ? " + (exclusive ? "FOR UPDATE" : "FOR SHARE"),
                (rs, index) -> !rs.getBoolean(1), id).stream().findFirst().orElse(false);
    }

    CommentRow find(long id, boolean lock) {
        return sql.query("SELECT " + COLUMNS + " FROM article_comment WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                ROW, id).stream().findFirst().orElse(null);
    }

    Map<Long, CommentRow> findAll(Collection<Long> ids) {
        var result = new HashMap<Long, CommentRow>();
        if (!ids.isEmpty()) {
            sql.query("SELECT " + COLUMNS + " FROM article_comment WHERE deleted = FALSE AND id IN (" + placeholders(ids.size()) + ")",
                    ROW, ids.toArray()).forEach(row -> result.put(row.id(), row));
        }
        return result;
    }

    CommentRow insert(long article, long root, Long replyTo, long author, String content, Instant now) {
        var key = new GeneratedKeyHolder();
        sql.update(connection -> {
            var write = connection.prepareStatement("""
                    INSERT INTO article_comment(article_id, root_id, reply_to_id, author_id, content, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            write.setLong(1, article);
            write.setLong(2, root);
            write.setObject(3, replyTo);
            write.setLong(4, author);
            write.setString(5, content);
            write.setObject(6, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return write;
        }, key);
        long id = Objects.requireNonNull(key.getKey()).longValue();
        if (root == 0) {
            sql.update("INSERT INTO comment_like_count(comment_id) VALUES (?)", id);
            changeCount(article, 1);
        }
        return new CommentRow(id, article, root, replyTo, author, content, now, false);
    }

    void delete(CommentRow row) {
        int changed = sql.update("UPDATE article_comment SET deleted = TRUE, content = NULL WHERE id = ? AND deleted = FALSE",
                row.id());
        if (changed != 1) throw new IllegalStateException("评论删除状态发生变化");
        if (row.rootId() == 0) changeCount(row.articleId(), -1);
    }

    private void changeCount(long article, int delta) {
        if (sql.update("UPDATE article SET comment_count = comment_count + ? WHERE id = ?", delta, article) != 1) {
            throw new IllegalStateException("文章评论计数缺失");
        }
    }

    Map<Long, Long> counts(Collection<Long> ids) {
        var result = new HashMap<Long, Long>();
        if (!ids.isEmpty()) {
            sql.query("SELECT id, comment_count FROM article WHERE id IN (" + placeholders(ids.size()) + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getLong(1), rs.getLong(2)),
                    ids.toArray());
        }
        return result;
    }

    /** 通过 Connector/J 流式读一个一致的 SELECT，排序由 Redis 定长键完成。 */
    void streamIndex(CommentListKey key, BiConsumer<Long, Long> row) {
        boolean hot = key.sort() == CommentListKey.Sort.HOT;
        String select = hot
                ? """
                    SELECT c.id, CASE WHEN c.deleted THEN 0 ELSE l.like_count END AS sort_value
                    FROM article_comment c JOIN comment_like_count l ON l.comment_id = c.id
                    WHERE c.article_id = ? AND c.root_id = ?
                    """
                : """
                    SELECT id, created_at FROM article_comment
                    WHERE article_id = ? AND root_id = ? AND (root_id = 0 OR deleted = FALSE)
                    """;
        sql.query(connection -> {
            var statement = connection.prepareStatement(select);
            statement.setLong(1, key.articleId());
            statement.setLong(2, key.rootId());
            statement.setFetchSize(Integer.MIN_VALUE);
            return statement;
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            long sortValue = hot ? rs.getLong(2)
                    : rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC).toEpochMilli();
            row.accept(rs.getLong(1), sortValue);
        });
    }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }
}
