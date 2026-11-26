package com.febrie.backend.article.comment.application.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults.Result;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 发布及重试使用同一个同步入口，事务提交后才组装响应。 */
@Service
public class CommentSubmissions {
    private static final Logger log = LoggerFactory.getLogger(CommentSubmissions.class);
    private final CommentSubmissionTransactions transactions;
    private final CommentCommandResults results;
    private final CommentStore store;
    private final CommentViews views;

    CommentSubmissions(CommentSubmissionTransactions transactions, CommentCommandResults results,
                       CommentStore store, CommentViews views) {
        this.transactions = transactions;
        this.results = results;
        this.store = store;
        this.views = views;
    }

    public View submit(long article, UserActor actor, String username, String submissionId, CommentInput input) {
        String id = CommentOperation.id(submissionId);
        // 正文按现有规则入库，摘要仍绑定客户端实际提交的原文。
        var normalized = new CommentInput(CommentWrites.content(input.content()), input.replyToId());
        String fingerprint = CommentOperation.submission(input);
        CommentSubmissionTransactions.Applied applied;
        try {
            applied = transactions.apply(article, actor, id, normalized, fingerprint);
        } catch (DuplicateKeyException concurrent) {
            // 事务已经回滚，重新读取获胜请求的结果，确认本次提交仍是同一载荷。
            var existing = results.find(article, actor.userId(), id);
            if (existing == null) throw concurrent;
            return previous(article, actor.userId(), username, existing.requireFingerprint(fingerprint));
        }
        var created = applied.created();
        if (created == null) return previous(article, actor.userId(), username, applied.result());

        var comment = created.row().rootId() == 0 ? views.submitted(created.row(), username)
                : views.createdReply(created.row(), username, created.targetAuthorId());
        return new View(State.PUBLISHED, comment);
    }

    private View previous(long article, long author, String username, Result result) {
        if (result.error() != null) return new View(State.FAILED, null);
        var row = store.findVisiblePublished(article, author, result.commentId());
        if (row == null) return new View(State.PUBLISHED, null);
        // 可见性已经由主库确认，资料和点赞读取失败时仍能返回原评论。
        try {
            return new View(State.PUBLISHED, views.published(row, author));
        } catch (DataAccessException failure) {
            log.warn("重试返回评论基础信息 commentId={}", row.id(), failure);
        } catch (ApiException failure) {
            if (!failure.status().is5xxServerError()) throw failure;
            log.warn("重试返回评论基础信息 commentId={} code={}", row.id(), failure.code());
        }
        return new View(State.PUBLISHED, views.submitted(row, username));
    }

    public enum State { PUBLISHED, FAILED }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(State state, CommentViews.Item comment) { }
}
