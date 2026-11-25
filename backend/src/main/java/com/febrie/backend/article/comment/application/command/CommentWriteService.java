package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.user.application.dto.UserActor;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 旧版接口共用的写入入口，一级评论异步受理，回复同步写入。 */
@Service
public class CommentWriteService {
    private final CommentAccess access;
    private final CommentCommands commands;
    private final CommentWrites writes;
    private final CommentViews views;

    CommentWriteService(CommentAccess access, CommentCommands commands, CommentWrites writes, CommentViews views) {
        this.access = access;
        this.commands = commands;
        this.writes = writes;
        this.views = views;
    }

    public Creation create(long article, UserActor actor, String username, String operation, CommentInput input) {
        if (input.replyToId() == null) {
            access.article(article);
            return commands.submit(new CommentCommand(article, actor, operation, null, input.content()));
        }
        var row = writes.createReply(article, actor, input.replyToId(), input.content());
        return new CreatedReply(views.created(row, username));
    }

    /** 一级评论返回异步凭证，回复删除完成后返回空结果。 */
    public Optional<Accepted> delete(long article, long comment, UserActor actor, String operation) {
        if (writes.deletionTarget(article, comment, actor).rootId() == 0) {
            access.article(article);
            return Optional.of(commands.submit(new CommentCommand(article, actor, operation, comment, null)));
        }
        writes.deleteReply(article, comment, actor);
        return Optional.empty();
    }

    public Status status(long article, UserActor actor, String operation, String receipt) {
        return commands.status(article, actor, operation, receipt);
    }

    /** 创建结果区分异步受理与已生成的回复。 */
    public sealed interface Creation permits Accepted, CreatedReply { }

    public record Accepted(String operationId, String receipt) implements Creation { }

    public record CreatedReply(CommentViews.Item item) implements Creation { }

    public record Status(String operationId, String status, String commentId, String errorCode) { }
}
