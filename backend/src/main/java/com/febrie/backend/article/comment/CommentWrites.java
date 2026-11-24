package com.febrie.backend.article.comment;

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
    public CommentRow create(long article, UserActor actor, Long replyToId, String supplied) {
        String content = content(supplied);
        if (!store.lockPublicArticle(article, replyToId == null)) throw CommentErrors.missing();
        long rootId = 0;
        if (replyToId != null) {
            CommentRow candidate = belonging(article, store.find(replyToId, false));
            rootId = candidate.rootId() == 0 ? candidate.id() : candidate.rootId();
            var root = belonging(article, store.find(rootId, true));
            var target = replyToId == rootId ? root : belonging(article, store.find(replyToId, true));
            if (root.deleted() || target.deleted()) throw CommentErrors.missing();
        }
        var inserted = store.insert(article, rootId, replyToId, actor.userId(), content,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        if (rootId == 0) {
            // 滚动升级时同步入口也必须登记索引变更。
            store.changeCount(article, 1);
            store.appendIndex(inserted.id());
            afterCommit(() -> maintenance.count(article));
        }
        return inserted;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
    public void delete(long article, long id, UserActor actor) {
        // 层级不可修改，可在加文章锁之前确定是否需要写计数。
        var candidate = belonging(article, store.find(id, false));
        if (!store.lockPublicArticle(article, candidate.rootId() == 0)) throw CommentErrors.missing();
        if (candidate.rootId() > 0) belonging(article, store.find(candidate.rootId(), true));
        var row = belonging(article, store.find(id, true));
        requireDeletePermission(row, actor);
        if (row.deleted()) return;
        store.delete(row);
        if (row.rootId() == 0) {
            store.changeCount(article, -1);
            store.appendIndex(row.id());
        }
        afterCommit(() -> maintenance.deleted(row));
    }

    CommentRow deletionTarget(long article, long id, UserActor actor) {
        var row = belonging(article, store.find(id, false));
        requireDeletePermission(row, actor);
        return row;
    }

    static void requireDeletePermission(CommentRow row, UserActor actor) {
        if (row.authorId() != actor.userId() && actor.role() != Role.ADMIN && actor.role() != Role.PATROLLER) {
            throw CommentErrors.forbidden();
        }
    }

    static CommentRow belonging(long article, CommentRow row) {
        if (row == null || row.articleId() != article) throw CommentErrors.missing();
        return row;
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
