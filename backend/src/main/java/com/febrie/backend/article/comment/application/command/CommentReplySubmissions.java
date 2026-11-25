package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 回复正文与提交结果同事务提交。并发撞到唯一键时，两条记录一起回滚。 */
@Service
class CommentReplySubmissions {
    private final CommentWrites writes;
    private final CommentCommandResults results;

    CommentReplySubmissions(CommentWrites writes, CommentCommandResults results) {
        this.writes = writes;
        this.results = results;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public CommentCommandResults.Result apply(long article, UserActor actor, String id, CommentInput input, String fingerprint) {
        var existing = results.find(article, actor.userId(), id);
        if (existing != null) return existing.requireFingerprint(fingerprint);

        Long commentId = null;
        String error = null;
        try {
            commentId = writes.insertReply(article, actor, input.replyToId(), input.content()).id();
        } catch (ApiException failure) {
            if (failure.status() != HttpStatus.NOT_FOUND) throw failure;
            error = failure.code();
        }
        results.insert(article, actor.userId(), id, fingerprint, commentId, error);
        return new CommentCommandResults.Result(fingerprint, commentId, error);
    }
}
