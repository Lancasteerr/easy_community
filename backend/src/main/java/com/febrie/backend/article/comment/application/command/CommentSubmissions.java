package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.CommentErrors;
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

    CommentSubmissions(CommentCommands commands, CommentReplySubmissions replies, CommentCommandResults results,
                       CommentAccess access, CommentStore store) {
        this.commands = commands;
        this.replies = replies;
        this.results = results;
        this.access = access;
        this.store = store;
    }

    public View submit(long article, UserActor actor, String submissionId, CommentInput supplied) {
        String id = CommentCommand.operation(submissionId);
        var input = new CommentInput(CommentWrites.content(supplied.content()), supplied.replyToId());
        if (input.replyToId() == null) {
            var command = new CommentCommand(article, actor, id, null, input.content());
            var existing = results.find(article, actor.userId(), id);
            if (existing != null) return view(article, existing.requireFingerprint(command.fingerprint()), true);
            try {
                access.article(article);
            } catch (ApiException failure) {
                if (failure.status() == HttpStatus.NOT_FOUND) return new View(State.FAILED, null);
                throw failure;
            }
            commands.enqueue(command);
            return new View(State.PUBLISHING, null);
        }

        String fingerprint = CommentCommand.digest("reply:" + input.replyToId() + ":" + input.content());
        try {
            return view(article, replies.apply(article, actor, id, input, fingerprint), false);
        } catch (DuplicateKeyException concurrent) {
            // 事务已经回滚，重新读取获胜请求的结果，确认本次提交仍是同一载荷。
            var existing = results.find(article, actor.userId(), id);
            if (existing == null) throw concurrent;
            return view(article, existing.requireFingerprint(fingerprint), false);
        }
    }

    public View status(long article, UserActor actor, String submissionId) {
        var result = results.find(article, actor.userId(), CommentCommand.operation(submissionId));
        return view(article, result, true);
    }

    /** 仅允许从同一登录用户已发布的回复确认结果刷新对应楼层。 */
    public void requirePublishedReply(long article, long root, UserActor actor, String submissionId) {
        var result = results.find(article, actor.userId(), CommentCommand.operation(submissionId));
        if (result == null || result.error() != null || result.commentId() == null) throw CommentErrors.missing();
        var row = CommentAccess.belonging(article, store.find(result.commentId(), false));
        if (row.rootId() != root || row.authorId() != actor.userId() || row.deleted()) throw CommentErrors.missing();
    }

    private View view(long article, CommentCommandResults.Result result, boolean includeCount) {
        if (result == null) return new View(State.PUBLISHING, null);
        if (result.error() != null) return new View(State.FAILED, null);
        // 读取主库已提交的总数，避免确认响应携带尚未失效的计数缓存。
        try {
            return new View(State.PUBLISHED, includeCount ? store.counts(List.of(article)).get(article) : null);
        } catch (DataAccessException failure) {
            log.warn("评论已发布，但计数读取失败 articleId={}", article, failure);
            return new View(State.PUBLISHED, null);
        }
    }

    public enum State { PUBLISHING, PUBLISHED, FAILED }

    public record View(State state, @JsonInclude(JsonInclude.Include.NON_NULL) Long commentCount) { }
}
