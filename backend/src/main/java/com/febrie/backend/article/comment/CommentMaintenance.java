package com.febrie.backend.article.comment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 删除提交成功后尽快关闭旧正文窗口；失败保留已登记的主库补偿。 */
@Component
public class CommentMaintenance {
    private static final Logger log = LoggerFactory.getLogger(CommentMaintenance.class);
    private final CommentStore store;
    private final CommentValues values;

    public CommentMaintenance(CommentStore store, CommentValues values) {
        this.store = store;
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
        publishTombstone(row);
        if (row.rootId() == 0) count(row.articleId());
    }

    private void publishTombstone(CommentRow row) {
        try {
            if (values.tombstone(row)) store.completeRepair(row.id());
        } catch (RuntimeException failure) {
            log.warn("评论墓碑待补偿 commentId={}", row.id(), failure);
        }
    }

    void repair() {
        for (long id : store.dueRepairs()) {
            if (!store.claimRepair(id)) continue;
            var row = store.find(id, false);
            if (row != null && row.deleted()) publishTombstone(row);
        }
    }
}
