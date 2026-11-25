package com.febrie.backend.article.comment.infrastructure.persistence;

import com.febrie.backend.article.comment.application.CommentErrors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 幂等结果与业务变更使用同一主库事务，唯一键负责裁决重复提交。 */
@Repository
public class CommentCommandResults {
    private final JdbcTemplate sql;

    public CommentCommandResults(JdbcTemplate sql) {
        this.sql = sql;
    }

    public Result find(long article, long actor, String operation) {
        return sql.query("""
                SELECT fingerprint, comment_id, error_code FROM comment_write_result
                WHERE article_id = ? AND actor_id = ? AND operation_id = ?
                """, (rs, index) -> new Result(rs.getString(1), rs.getObject(2, Long.class), rs.getString(3)),
                article, actor, operation).stream().findFirst().orElse(null);
    }

    public void insert(long article, long actor, String operation, String fingerprint, Long id, String error) {
        sql.update("""
                INSERT INTO comment_write_result(article_id, actor_id, operation_id, fingerprint, comment_id, error_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, article, actor, operation, fingerprint, id, error);
    }

    public record Result(String fingerprint, Long commentId, String error) {
        public Result requireFingerprint(String expected) {
            if (!fingerprint.equals(expected)) throw CommentErrors.operationConflict();
            return this;
        }
    }
}
