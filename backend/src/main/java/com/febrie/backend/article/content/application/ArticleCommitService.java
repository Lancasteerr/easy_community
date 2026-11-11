package com.febrie.backend.article.content.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.dto.PreparedArticle;
import com.febrie.backend.article.content.infrastructure.persistence.ArticleRepository;

import com.febrie.backend.media.application.reference.MediaReferenceService;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** 由ArticleService完成标签校验后经Spring代理调用；文章行先于文件行加锁。 */
@Service
public class ArticleCommitService {
    private final ArticleRepository articles;
    private final MediaReferenceService references;
    private final Clock clock;

    public ArticleCommitService(ArticleRepository articles, MediaReferenceService references, Clock clock) {
        this.articles = articles; this.references = references; this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long create(UserActor actor, PreparedArticle content, Attempt attempt) {
        attempt.observe();
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        long id = articles.insert(actor.userId(), content, now);
        attempt.id = id;
        replace(id, actor.userId(), content, now);
        articles.recordIndexEvent(id, false);
        attempt.bodyCompleted = true;
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public long update(UserActor actor, long id, PreparedArticle content, Attempt attempt) {
        attempt.observe();
        var current = articles.lock(id).orElseThrow(ArticleErrors::notFound);
        if (current.authorId() != actor.userId()) { throw ArticleErrors.forbidden(); }
        if (current.hidden()) { throw ArticleErrors.hidden(); }
        replace(id, current.authorId(), content, clock.instant().truncatedTo(ChronoUnit.MILLIS));
        articles.recordIndexEvent(id, true);
        attempt.id = id;
        attempt.bodyCompleted = true;
        return id;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void hide(UserActor actor, long id, Attempt attempt) {
        attempt.observe();
        var current = articles.lock(id).orElseThrow(ArticleErrors::notFound);
        if (current.authorId() != actor.userId() && actor.role() != Role.ADMIN && actor.role() != Role.PATROLLER) {
            throw ArticleErrors.forbidden();
        }
        // 已隐藏也完成权限检查；不解绑媒体、不允许恢复，重复删除可再次修复缓存。
        if (!current.hidden()) {
            articles.hide(id, clock.instant().truncatedTo(ChronoUnit.MILLIS));
            articles.recordIndexEvent(id, true);
        }
        attempt.id = id;
        attempt.bodyCompleted = true;
    }

    private void replace(long id, long author, PreparedArticle content, java.time.Instant now) {
        references.syncArticle(id, author, content.bodyMediaIds(), content.coverMediaId());
        articles.replace(id, content, now);
    }

    /** 仅当前调用的内存确认，不是业务版本、幂等键或持久化操作状态。 */
    static final class Attempt {
        long id;
        boolean bodyCompleted;
        boolean committed;
        void observe() {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed = true; }
            });
        }
    }
}
