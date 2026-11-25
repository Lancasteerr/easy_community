package com.febrie.backend.article.comment.application.query;

import com.febrie.backend.article.comment.infrastructure.redis.CommentValues;
import java.util.Collection;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 批量读取文章的一级评论数，供列表和其他文章能力复用。 */
@Service
public class CommentCountQuery {
    private final CommentValues values;

    CommentCountQuery(CommentValues values) {
        this.values = values;
    }

    public Map<Long, Long> counts(Collection<Long> articleIds) {
        return values.counts(articleIds);
    }
}
