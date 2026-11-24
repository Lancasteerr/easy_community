package com.febrie.backend.article.comment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 幂等结果与业务变更使用同一主库事务，唯一键负责裁决重复提交。 */
@Repository
class CommentCommandResults {
    private final JdbcTemplate sql;

    CommentCommandResults(JdbcTemplate sql) {
        this.sql = sql;
    }

    Result find(long article, long actor, String operation) {
        return sql.query("""
                SELECT fingerprint, comment_id, error_code FROM comment_write_result
                WHERE article_id = ? AND actor_id = ? AND operation_id = ?
                """, (rs, index) -> new Result(rs.getString(1), rs.getObject(2, Long.class), rs.getString(3)),
                article, actor, operation).stream().findFirst().orElse(null);
    }

    void insert(CommentCommand command, Long id, String error) {
        insert(command.articleId(), command.actor().userId(), command.operationId(), command.fingerprint(), id, error);
    }

    void insert(long article, long actor, String operation, String fingerprint, Long id, String error) {
        sql.update("""
                INSERT INTO comment_write_result(article_id, actor_id, operation_id, fingerprint, comment_id, error_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, article, actor, operation, fingerprint, id, error);
    }

    record Result(String fingerprint, Long commentId, String error) {
        Result requireFingerprint(String expected) {
            if (!fingerprint.equals(expected)) throw CommentErrors.operationConflict();
            return this;
        }
    }
}
