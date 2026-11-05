package com.febrie.backend.article.like.application;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 单个一致性快照做全表聚合，仅离线报告；没有反向索引，不安排在线逐文章扫描。 */
@Service
public class LikeAuditService {
    private final JdbcTemplate jdbc;
    public LikeAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> discrepancies() {
        return jdbc.queryForList("""
            SELECT ids.article_id, c.like_count, COALESCE(s.actual_count,0) AS actual_count
            FROM (SELECT id AS article_id FROM article UNION SELECT article_id FROM article_like_count
                  UNION SELECT article_id FROM article_like_state) ids
            LEFT JOIN article_like_count c ON c.article_id=ids.article_id
            LEFT JOIN (SELECT article_id,SUM(liked) AS actual_count FROM article_like_state GROUP BY article_id) s
            ON s.article_id=ids.article_id
            WHERE c.article_id IS NULL OR c.like_count != COALESCE(s.actual_count,0)
            """);
    }
}
