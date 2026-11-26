package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同一文章由单活分片顺序重算，历史消息也只读取当前的评论事实。 */
@Service
public class CommentCountProjection {
    private final CommentStore store;

    CommentCountProjection(CommentStore store) { this.store = store; }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void recount(Collection<Long> articles) {
        store.recount(articles.stream().distinct().sorted().toList());
    }
}
