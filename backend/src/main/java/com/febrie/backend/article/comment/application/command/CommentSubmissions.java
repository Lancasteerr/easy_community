package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 发布接口使用会话与提交编号查询落库结果，界面无需感知消息或签名协议。 */
@Service
public class CommentSubmissions {
    private static final Logger log = LoggerFactory.getLogger(CommentSubmissions.class);
    private final CommentCommands commands;
    private final CommentReplySubmissions replies;
    private final CommentCommandResults results;
    private final CommentAccess access;
    private final CommentStore store;
    private final CommentViews views;

    CommentSubmissions(CommentCommands commands, CommentReplySubmissions replies, CommentCommandResults results,
                       CommentAccess access, CommentStore store, CommentViews views) {
        this.commands = commands;
        this.replies = replies;
        this.results = results;
        this.access = access;
        this.store = store;
        this.views = views;
    }

    public View submit(long article, UserActor actor, String username, String submissionId, CommentInput supplied) {
        long author = actor.userId();
        String id = CommentCommand.operation(submissionId);
        var input = new CommentInput(CommentWrites.content(supplied.content()), supplied.replyToId());
        if (input.replyToId() == null) {
            var command = new CommentCommand(article, actor, id, null, input.content());
            var existing = results.find(article, author, id);
            if (existing != null) return view(article, author, existing.requireFingerprint(command.fingerprint()), true);
            try {
                access.article(article);
            } catch (ApiException failure) {
                if (failure.status() == HttpStatus.NOT_FOUND) return new View(State.FAILED, null, null);
                throw failure;
            }
            commands.enqueue(command);
            return new View(State.PUBLISHING, null, null);
        }

        String fingerprint = CommentCommand.digest("reply:" + input.replyToId() + ":" + input.content());
        CommentReplySubmissions.Applied applied;
        try {
            applied = replies.apply(article, actor, id, input, fingerprint);
        } catch (DuplicateKeyException concurrent) {
            // 事务已经回滚，重新读取获胜请求的结果，确认本次提交仍是同一载荷。
            var existing = results.find(article, author, id);
            if (existing == null) throw concurrent;
            return view(article, author, existing.requireFingerprint(fingerprint), false);
        }
        var created = applied.created();
        if (created == null) return view(article, author, applied.result(), false);

        // 事务已提交，首次回应直接使用写入结果，避免再查刚创建的回复。
        return new View(State.PUBLISHED, null, views.createdReply(created.row(), username, created.targetAuthorId()));
    }

    public View status(long article, UserActor actor, String submissionId) {
        var result = results.find(article, actor.userId(), CommentCommand.operation(submissionId));
        return view(article, actor.userId(), result, true);
    }

    private View view(long article, long author, CommentCommandResults.Result result, boolean includeCount) {
        if (result == null) return new View(State.PUBLISHING, null, null);
        if (result.error() != null) return new View(State.FAILED, null, null);
        Long count = includeCount ? publishedCount(article) : null;
        var comment = result.commentId() == null ? null : publishedComment(article, author, result.commentId());
        return new View(State.PUBLISHED, count, comment);
    }

    private Long publishedCount(long article) {
        // 读取主库已提交的总数，避免确认响应携带尚未失效的计数缓存。
        try {
            return store.counts(List.of(article)).get(article);
        } catch (DataAccessException failure) {
            log.warn("评论已发布，但计数读取失败 articleId={}", article, failure);
            return null;
        }
    }

    private CommentViews.Item publishedComment(long article, long author, long commentId) {
        // DTO 附带当前可见内容。读取故障时，持久化的提交结果仍然有效。
        try {
            var row = store.findVisiblePublished(article, author, commentId);
            return row == null ? null : views.published(row, author);
        } catch (DataAccessException failure) {
            log.warn("评论已发布，但内容读取失败 commentId={}", commentId, failure);
            return null;
        } catch (ApiException failure) {
            if (!failure.status().is5xxServerError()) throw failure;
            log.warn("评论已发布，但内容组装失败 commentId={} code={}", commentId, failure.code());
            return null;
        }
    }

    public enum State { PUBLISHING, PUBLISHED, FAILED }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(State state, Long commentCount, CommentViews.Item comment) { }
}
