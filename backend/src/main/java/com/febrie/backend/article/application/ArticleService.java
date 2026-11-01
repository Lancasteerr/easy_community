package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.SaveArticleCommand;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import java.sql.SQLException;
import java.util.function.LongSupplier;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;

/** 不承载外层事务：准备、一次代理提交、提交后缓存维护必须保持三个独立阶段。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class ArticleService {
    private static final Logger log = LoggerFactory.getLogger(ArticleService.class);
    private final ArticleContentRenderer renderer;
    private final ArticleCommitService commits;

    public ArticleService(ArticleContentRenderer renderer, ArticleCommitService commits) {
        this.renderer = renderer; this.commits = commits;
    }

    public long save(UserActor actor, SaveArticleCommand command) {
        var content = renderer.prepare(command.title(), command.content(), command.coverMediaId(), command.tagIds());
        var attempt = new ArticleCommitService.Attempt();
        return execute(attempt, () -> command.articleId() == null ? commits.create(actor, content, attempt)
                : commits.update(actor, command.articleId(), content, attempt));
    }

    public void hide(UserActor actor, long id) {
        var attempt = new ArticleCommitService.Attempt();
        execute(attempt, () -> { commits.hide(actor, id, attempt); return id; });
    }

    private long execute(ArticleCommitService.Attempt attempt, LongSupplier operation) {
        try { return operation.getAsLong(); }
        catch (RuntimeException error) {
            // afterCommit已确认但清理失败时不能把已提交写入报成失败。
            if (attempt.committed) {
                log.warn("文章提交后清理异常 articleId={} error={}", attempt.id, error.getClass().getSimpleName());
                return attempt.id;
            }
            if (error instanceof ApiException api) { throw api; }
            if (error instanceof DataAccessException || error instanceof TransactionException) {
                boolean unknown = attempt.bodyCompleted && !(error instanceof UnexpectedRollbackException) && !explicitRejection(error);
                log.error("文章事务异常 articleId={} result={} error={}", attempt.id,
                        unknown ? "UNKNOWN" : "NOT_COMMITTED", error.getClass().getSimpleName());
                throw unknown ? ArticleErrors.unknown() : ArticleErrors.unavailable();
            }
            throw error;
        }
    }

    /** 只认可数据库明确拒绝的事务类SQLSTATE，不能把通信异常后的回滚回调当作提交证明。 */
    private static boolean explicitRejection(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("40")) { return true; }
        }
        return false;
    }
}
