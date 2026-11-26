package com.febrie.backend.article.comment.infrastructure.persistence;

import com.febrie.backend.article.comment.application.query.CommentListKey;
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
import org.springframework.jdbc.core.RowCallbackHandler;
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

    /** 删除回复只检查查询时的文章可见性。 */
    public boolean isPublicArticle(long id) {
        return Boolean.TRUE.equals(sql.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM article WHERE id = ? AND hidden = FALSE)", Boolean.class, id));
    }

    public boolean lockPublicArticle(long id, boolean exclusive) {
        return sql.query("SELECT hidden FROM article WHERE id = ? " + (exclusive ? "FOR UPDATE" : "FOR SHARE"),
                (rs, index) -> !rs.getBoolean(1), id).stream().findFirst().orElse(false);
    }

    /** 文章共享锁已阻止隐藏和删除一级评论，查询仅对实际回复目标加共享锁。 */
    public ReplyTarget lockReplyTargetAndFindRoot(long article, long replyToId) {
        return sql.query("""
                SELECT root.id, target.author_id
                FROM article_comment target
                JOIN article_comment root
                  ON root.id = CASE WHEN target.root_id = 0 THEN target.id ELSE target.root_id END
                 AND root.article_id = target.article_id
                 AND root.root_id = 0 AND root.deleted = FALSE
                WHERE target.id = ? AND target.article_id = ? AND target.deleted = FALSE
                FOR SHARE OF target
                """, (rs, index) -> new ReplyTarget(article, rs.getLong(1), rs.getLong(2)), replyToId, article)
                .stream().findFirst().orElse(null);
    }

    public CommentRow find(long id, boolean lock) {
        return sql.query("SELECT " + COLUMNS + " FROM article_comment WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                ROW, id).stream().findFirst().orElse(null);
    }

    /** 仅在回显已提交结果时读取单条评论，同时检查文章及楼层的当前可见性。 */
    public CommentRow findVisiblePublished(long article, long author, long id) {
        return sql.query("SELECT " + COLUMNS + " " + """
                FROM article_comment c
                 WHERE c.id = ? AND c.article_id = ? AND c.author_id = ? AND c.deleted = FALSE
                   AND EXISTS (SELECT 1 FROM article a WHERE a.id = c.article_id AND a.hidden = FALSE)
                   AND (c.root_id = 0 OR EXISTS (
                       SELECT 1 FROM article_comment root
                       WHERE root.id = c.root_id AND root.article_id = c.article_id
                         AND root.root_id = 0 AND root.deleted = FALSE
                   ))
                """, ROW, id, article, author).stream().findFirst().orElse(null);
    }

    public Map<Long, CommentRow> findAll(Collection<Long> ids) {
        var result = new HashMap<Long, CommentRow>();
        if (!ids.isEmpty()) {
            sql.query("SELECT " + COLUMNS + " FROM article_comment WHERE deleted = FALSE AND id IN (" + placeholders(ids.size()) + ")",
                    ROW, ids.toArray()).forEach(row -> result.put(row.id(), row));
        }
        return result;
    }

    /** 目标软删除后仍保留归属与作者；这里不读取正文，也不参与可见性判断。 */
    public Map<Long, ReplyTarget> findReplyTargets(Collection<Long> ids) {
        var result = new HashMap<Long, ReplyTarget>();
        if (!ids.isEmpty()) {
            sql.query("SELECT id, article_id, root_id, author_id FROM article_comment WHERE id IN ("
                    + placeholders(ids.size()) + ")", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        long id = rs.getLong("id");
                        long rootId = rs.getLong("root_id");
                        result.put(id, new ReplyTarget(rs.getLong("article_id"), rootId == 0 ? id : rootId,
                                rs.getLong("author_id")));
                    }, ids.toArray());
        }
        return result;
    }

    /** rootId 始终是所属楼层 ID；目标本身是一级评论时取目标自己的 ID。 */
    public record ReplyTarget(long articleId, long rootId, long authorId) { }

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

    /** 只有首次软删除返回成功，一级评论同时递增索引版本。 */
    public boolean deleteIfActive(CommentRow row) {
        return sql.update("""
                UPDATE article_comment
                SET deleted = TRUE, content = NULL, index_version = index_version + (root_id = 0)
                WHERE id = ? AND deleted = FALSE
                """, row.id()) == 1;
    }

    public void appendIndex(long id) {
        if (sql.update("""
                INSERT INTO comment_index_outbox(comment_id, index_version)
                SELECT id, index_version FROM article_comment WHERE id = ? AND root_id = 0
                """, id) != 1) throw new IllegalStateException("一级评论索引事件写入失败");
    }

    /** 调用方已去重并按 ID 排序，同一批次按固定顺序取得文章行锁。 */
    public void recount(List<Long> articles) {
        if (articles.isEmpty()) return;
        var totals = new HashMap<Long, Long>();
        sql.query("""
                SELECT article_id, COUNT(*) FROM article_comment
                WHERE root_id = 0 AND deleted = FALSE AND article_id IN (%s)
                GROUP BY article_id
                """.formatted(placeholders(articles.size())),
                (RowCallbackHandler) rs -> totals.put(rs.getLong(1), rs.getLong(2)), articles.toArray());
        // 没有有效楼层的文章没有分组结果，仍需把已物化的旧计数归零。
        var updates = articles.stream().map(article -> new Object[]{totals.getOrDefault(article, 0L), article}).toList();
        sql.batchUpdate("UPDATE article SET comment_count = ? WHERE id = ?", updates);
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
