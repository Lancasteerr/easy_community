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
public class CommentWrites {
    private final CommentStore store;
    private final CommentMaintenance maintenance;
    private final Clock clock;

    public CommentWrites(CommentStore store, CommentMaintenance maintenance, Clock clock) {
        this.store = store;
        this.maintenance = maintenance;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public CommentRow createReply(long article, UserActor actor, long replyToId, String supplied) {
        return insertReply(article, actor, replyToId, content(supplied));
    }

    /** 调用方先校验文字，并开启事务以持有文章与楼层锁。 */
    CommentRow insertReply(long article, UserActor actor, long replyToId, String content) {
        if (!store.lockPublicArticle(article, false)) throw CommentErrors.missing();
        var candidate = CommentAccess.belonging(article, store.find(replyToId, false));
        long rootId = candidate.rootId() == 0 ? candidate.id() : candidate.rootId();
        // 固定文章、楼层、实际目标的加锁顺序，与删楼串行。
        var root = CommentAccess.belonging(article, store.find(rootId, true));
        var target = replyToId == rootId ? root : CommentAccess.belonging(article, store.find(replyToId, true));
        if (root.deleted() || target.deleted()) throw CommentErrors.missing();
        return store.insert(article, rootId, replyToId, actor.userId(), content,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void deleteReply(long article, long id, UserActor actor) {
        if (!store.lockPublicArticle(article, false)) throw CommentErrors.missing();
        var candidate = CommentAccess.belonging(article, store.find(id, false));
        if (candidate.rootId() == 0) throw CommentErrors.invalid();
        CommentAccess.belonging(article, store.find(candidate.rootId(), true));
        var row = CommentAccess.belonging(article, store.find(id, true));
        requireDeletePermission(row, actor);
        if (row.deleted()) return;
        store.delete(row);
        afterCommit(() -> maintenance.visibility(row));
    }

    public CommentRow deletionTarget(long article, long id, UserActor actor) {
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
