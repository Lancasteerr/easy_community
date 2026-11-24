package com.febrie.backend.article.comment;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 事务提交后尽力清理缓存，失败由硬 TTL 收敛。 */
@Component
public class CommentMaintenance {
    private static final Logger log = LoggerFactory.getLogger(CommentMaintenance.class);
    private final MeterRegistry metrics;
    private final CommentValues values;

    public CommentMaintenance(MeterRegistry metrics, CommentValues values) {
        this.metrics = metrics;
        this.values = values;
    }

    void count(long article) {
        try {
            values.invalidateCount(article);
        } catch (RuntimeException failure) {
            log.warn("文章评论数缓存失效失败 articleId={}", article, failure);
        }
    }

    void deleted(CommentRow row) {
        try {
            if (!values.invalidateObject(row.id())) {
                metrics.counter("community.comment.invalidate.failure").increment();
                log.warn("评论缓存失效锁竞争 commentId={}", row.id());
            }
        } catch (RuntimeException failure) {
            metrics.counter("community.comment.invalidate.failure").increment();
            log.warn("评论缓存失效失败 commentId={}", row.id(), failure);
        }
        if (row.rootId() == 0) count(row.articleId());
    }
}
