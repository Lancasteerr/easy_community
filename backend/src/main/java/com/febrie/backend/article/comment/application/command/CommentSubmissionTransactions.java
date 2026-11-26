package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults.Result;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 评论事实、幂等结果和后台事件必须一起提交。 */
@Service
class CommentSubmissionTransactions {
    private final CommentWrites writes;
    private final CommentCommandResults results;

    CommentSubmissionTransactions(CommentWrites writes, CommentCommandResults results) {
        this.writes = writes;
        this.results = results;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public Applied apply(long article, UserActor actor, String id, CommentInput input, String fingerprint) {
        var existing = results.find(article, actor.userId(), id);
        if (existing != null) return new Applied(existing.requireFingerprint(fingerprint), null);

        Created created = null;
        String error = null;
        try {
            if (input.replyToId() == null) {
                created = new Created(writes.insertRoot(article, actor, input.content()), null);
            } else {
                var reply = writes.insertReply(article, actor, input.replyToId(), input.content());
                created = new Created(reply.row(), reply.targetAuthorId());
            }
        } catch (ApiException failure) {
            if (failure.status() != HttpStatus.NOT_FOUND) throw failure;
            error = failure.code();
        }
        Long commentId = created == null ? null : created.row().id();
        results.insert(article, actor.userId(), id, fingerprint, commentId, error);
        return new Applied(new Result(fingerprint, commentId, error), created);
    }

    /** 调用方已检查归属和删除权限，只允许对一级评论调用。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public boolean deleteRoot(CommentRow target, long actor, String id, String fingerprint) {
        var existing = results.find(target.articleId(), actor, id);
        if (existing != null) {
            existing.requireFingerprint(fingerprint);
            return false;
        }
        boolean deleted = writes.deleteRoot(target);
        results.insert(target.articleId(), actor, id, fingerprint, target.id(), null);
        return deleted;
    }

    /** 首次发布使用已提交的正文；重放以结果表记录的评论 ID 为准。 */
    record Applied(Result result, Created created) { }
    record Created(CommentRow row, Long targetAuthorId) { }
}
