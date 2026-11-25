package com.febrie.backend.article.comment.infrastructure.persistence;

import com.febrie.backend.article.comment.CommentListKey;
import com.febrie.backend.article.comment.infrastructure.redis.CommentSnapshot;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** SQL 负责评论事实和 Outbox 持久化，网络操作由事务外的调用方处理。 */
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

    public boolean lockPublicArticle(long id, boolean exclusive) {
        return sql.query("SELECT hidden FROM article WHERE id = ? " + (exclusive ? "FOR UPDATE" : "FOR SHARE"),
                (rs, index) -> !rs.getBoolean(1), id).stream().findFirst().orElse(false);
    }

    public CommentRow find(long id, boolean lock) {
        return sql.query("SELECT " + COLUMNS + " FROM article_comment WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                ROW, id).stream().findFirst().orElse(null);
    }

    public Map<Long, CommentRow> findAll(Collection<Long> ids) {
        var result = new HashMap<Long, CommentRow>();
        if (!ids.isEmpty()) {
            sql.query("SELECT " + COLUMNS + " FROM article_comment WHERE deleted = FALSE AND id IN (" + placeholders(ids.size()) + ")",
                    ROW, ids.toArray()).forEach(row -> result.put(row.id(), row));
        }
        return result;
    }

    public CommentRow insert(long article, long root, Long replyTo, long author, String content, Instant now) {
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
        }
        return new CommentRow(id, article, root, replyTo, author, content, now, false);
    }

    public void delete(CommentRow row) {
        int changed = sql.update("UPDATE article_comment SET deleted = TRUE, content = NULL, index_version = index_version + (root_id = 0) WHERE id = ? AND deleted = FALSE",
                row.id());
        if (changed != 1) throw new IllegalStateException("评论删除状态发生变化");
    }

    public void appendIndex(long id) {
        if (sql.update("""
                INSERT INTO comment_index_outbox(comment_id, index_version)
                SELECT id, index_version FROM article_comment WHERE id = ? AND root_id = 0
                """, id) != 1) throw new IllegalStateException("一级评论索引事件写入失败");
    }

    public void changeCount(long article, int delta) {
        if (sql.update("UPDATE article SET comment_count = comment_count + ? WHERE id = ?", delta, article) != 1) {
            throw new IllegalStateException("文章评论计数缺失");
        }
    }

    public Map<Long, Long> counts(Collection<Long> ids) {
        var result = new HashMap<Long, Long>();
        if (!ids.isEmpty()) {
            sql.query("SELECT id, comment_count FROM article WHERE id IN (" + placeholders(ids.size()) + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getLong(1), rs.getLong(2)),
                    ids.toArray());
        }
        return result;
    }

    /** 每个冷楼层走有界索引查询，组装一级列表不会触发全楼重建。 */
    public Map<Long, List<CommentRow>> previews(long article, List<Long> roots) {
        var result = new HashMap<Long, List<CommentRow>>();
        for (int offset = 0; offset < roots.size(); offset += 16) {
            var group = roots.subList(offset, Math.min(offset + 16, roots.size()));
            var args = new ArrayList<Long>(group.size() * 2);
            for (long root : group) {
                args.add(article);
                args.add(root);
            }
            String part = "(SELECT " + COLUMNS + " " + """
                    FROM article_comment WHERE article_id = ? AND root_id = ? AND deleted = FALSE
                    ORDER BY created_at, id LIMIT 3)
                    """;
            String query = String.join(" UNION ALL ", java.util.Collections.nCopies(group.size(), part))
                    + " ORDER BY root_id, created_at, id";
            sql.query(query, ROW, args.toArray()).forEach(row ->
                    result.computeIfAbsent(row.rootId(), ignored -> new ArrayList<>(3)).add(row));
        }
        return result;
    }

    /** 发布后沿用回复游标补读一页，避免为了单次确认重建共享快照。 */
    public List<CommentRow> repliesAfter(CommentListKey key, String position, int limit) {
        String scope = "SELECT " + COLUMNS + " FROM article_comment WHERE article_id = ? AND root_id = ? AND deleted = FALSE ";
        String order = "ORDER BY created_at, id LIMIT ?";
        if (position.isEmpty()) return sql.query(scope + order, ROW, key.articleId(), key.rootId(), limit);
        var time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(position.substring(0, 19))), ZoneOffset.UTC);
        return sql.query(scope + "AND (created_at > ? OR (created_at = ? AND id > ?)) " + order, ROW,
                key.articleId(), key.rootId(), time, time, CommentSnapshot.commentId(position), limit);
    }

    /** 主库按不可变时间键读取，查询返回后才向 Redis 写这一批。 */
    public List<IndexRow> indexBatch(CommentListKey key, IndexRow after, int limit) {
        String select = "SELECT c.id, c.created_at FROM article_comment c ";
        String scope = "WHERE c.article_id = ? AND c.root_id = ? AND c.deleted = FALSE ";
        String order = "ORDER BY c.created_at, c.id LIMIT ?";
        RowMapper<IndexRow> mapper = (rs, index) -> {
            var created = rs.getObject(2, LocalDateTime.class);
            return new IndexRow(rs.getLong(1), created, created.toInstant(ZoneOffset.UTC).toEpochMilli());
        };
        if (after == null) return sql.query(select + scope + order, mapper, key.articleId(), key.rootId(), limit);
        return sql.query(select + scope + "AND (c.created_at > ? OR (c.created_at = ? AND c.id > ?)) " + order,
                mapper, key.articleId(), key.rootId(), after.createdAt(), after.createdAt(), after.id(), limit);
    }

    public record IndexRow(long id, LocalDateTime createdAt, long sortValue) { }

    private static String placeholders(int size) {
        return String.join(",", java.util.Collections.nCopies(size, "?"));
    }
}
