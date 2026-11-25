package com.febrie.backend.performance;

import com.febrie.backend.article.comment.infrastructure.search.BaselineCommentFixture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowCallbackHandler;

/** 二级写同步提交，停压后核对每笔响应、回复记录及文章一级评论数。 */
final class BaselineCommentReplyWrite {
    record Audit(long successful, long matched, long unexpectedResults, long unexpectedReplyRows, long wrongCounts) {
        boolean passed() {
            return successful > 0 && matched == successful && unexpectedResults == 0
                    && unexpectedReplyRows == 0 && wrongCounts == 0;
        }
    }

    private final BaselineEnvironment.Site site;
    private final Map<String, BaselineJournal.Event> successful = new HashMap<>();

    BaselineCommentReplyWrite(BaselineEnvironment.Site site) { this.site = site; }

    void append(List<BaselineJournal.Event> events, int offset) {
        for (var event : events.subList(offset, events.size())) {
            if (!event.name().equals("comment-reply-submit") || !event.ok()) continue;
            if (event.article() < 1 || event.article() > BaselineEnvironment.COMMENT_WRITE_ARTICLES
                    || event.submission() == null || successful.putIfAbsent(event.submission(), event) != null) {
                throw new IllegalStateException("回复成功记录缺少唯一提交 ID，或发压目标超出种子范围");
            }
        }
    }

    Audit audit() {
        var pending = new HashMap<>(successful);
        // 单独统计全部新增回复，能发现没有对应幂等结果或应答的孤立写入。
        long replyRows = site.jdbc.queryForObject("SELECT COUNT(*) FROM article_comment WHERE id > ? AND root_id > 0",
                Long.class, site.commentSeedMax());
        long resultRows = site.jdbc.queryForObject("SELECT COUNT(*) FROM comment_write_result", Long.class);
        site.jdbc.query("""
                SELECT r.operation_id, r.article_id, r.actor_id, c.root_id, c.reply_to_id, c.content
                FROM comment_write_result r JOIN article_comment c
                  ON c.id = r.comment_id AND c.article_id = r.article_id AND c.author_id = r.actor_id
                WHERE r.error_code IS NULL AND c.id > ? AND c.deleted = FALSE
                """, (RowCallbackHandler) row -> {
            String submission = row.getString("operation_id");
            var event = pending.get(submission);
            if (event == null || row.getLong("article_id") != event.article() || row.getLong("actor_id") != event.user()) return;
            long root = (event.article() - 1) * BaselineCommentFixture.ROOTS_PER_ARTICLE + 1;
            if (row.getLong("root_id") == root && row.getLong("reply_to_id") == root
                    && ("基线评论 " + submission).equals(row.getString("content"))) {
                pending.remove(submission);
            }
        }, site.commentSeedMax());
        long matched = successful.size() - pending.size();
        long correctCounts = site.jdbc.queryForObject("""
                SELECT COUNT(*) FROM article WHERE id BETWEEN 1 AND ? AND comment_count = ?
                """, Long.class, BaselineEnvironment.COMMENT_WRITE_ARTICLES, BaselineCommentFixture.ROOTS_PER_ARTICLE);
        return new Audit(successful.size(), matched, resultRows - matched, replyRows - matched,
                BaselineEnvironment.COMMENT_WRITE_ARTICLES - correctCounts);
    }
}
