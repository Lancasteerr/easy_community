package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.infrastructure.redis.CommentMaintenance;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 写入使用主库授权，提交以后才维护缓存；列表由 TTL 负责收敛。 */
@Service
class CommentWrites {
    private final CommentStore store;
    private final CommentMaintenance maintenance;
    private final Clock clock;

    CommentWrites(CommentStore store, CommentMaintenance maintenance, Clock clock) {
        this.store = store;
        this.maintenance = maintenance;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public CreatedReply createReply(long article, UserActor actor, long replyToId, String supplied) {
        return insertReply(article, actor, replyToId, content(supplied));
    }

    /** 调用方先校验文字，并在 READ COMMITTED 事务中持有共享锁直至提交。 */
    CreatedReply insertReply(long article, UserActor actor, long replyToId, String content) {
        if (!store.lockPublicArticle(article, false)) throw CommentErrors.missing();
        var target = store.lockReplyTargetAndFindRoot(article, replyToId);
        if (target == null) throw CommentErrors.missing();
        var row = store.insert(article, target.rootId(), replyToId, actor.userId(), content,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        return new CreatedReply(row, target.authorId());
    }

    /** 携带已经校验的目标作者，供事务外组装发布响应。 */
    record CreatedReply(CommentRow row, long targetAuthorId) { }

    /** 调用方已校验归属和删除权限，并确认目标是二级评论。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void deleteReply(CommentRow row) {
        if (!store.isPublicArticle(row.articleId())) throw CommentErrors.missing();
        if (!row.deleted() && store.deleteIfActive(row)) {
            afterCommit(() -> maintenance.visibility(row));
        }
    }

    CommentRow deletionTarget(long article, long id, UserActor actor) {
        var row = CommentAccess.belonging(article, store.find(id, false));
        requireDeletePermission(row, actor);
        return row;
    }

    static void requireDeletePermission(CommentRow row, UserActor actor) {
        if (row.authorId() != actor.userId() && actor.role() != Role.ADMIN && actor.role() != Role.PATROLLER) {
            throw CommentErrors.forbidden();
        }
    }

    static String content(String value) {
        if (value == null) throw CommentErrors.invalid();
        String stripped = value.strip();
        int length = stripped.codePointCount(0, stripped.length());
        if (length < 1 || length > 300 || stripped.codePoints().anyMatch(c -> c >= 0xD800 && c <= 0xDFFF)) {
            throw CommentErrors.invalid();
        }
        return stripped;
    }

    private static void afterCommit(Runnable callback) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { callback.run(); }
        });
    }
}
