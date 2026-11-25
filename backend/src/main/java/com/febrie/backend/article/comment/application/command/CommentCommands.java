package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.CommentCursorCodec;
import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.user.application.dto.UserActor;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** 受理凭证由会话主体及摘要签名，允许在消费者写结果前查询待处理状态。 */
@Service
public class CommentCommands {
    private final CommentBroker broker;
    private final CommentCommandResults results;
    private final CommentCursorCodec tokens;
    private final Clock clock;

    CommentCommands(CommentBroker broker, CommentCommandResults results, CommentCursorCodec tokens, Clock clock) {
        this.broker = broker;
        this.results = results;
        this.tokens = tokens;
        this.clock = clock;
    }

    public Accepted submit(CommentCommand command) {
        enqueue(command);
        var receipt = new Receipt(command.articleId(), command.actor().userId(), command.operationId(), command.fingerprint(),
                clock.millis() + Duration.ofDays(1).toMillis());
        return new Accepted(command.operationId(), tokens.seal(receipt));
    }

    /** 新的发布入口只复用可靠投递，原入口仍签发其查询凭证。 */
    void enqueue(CommentCommand command) {
        String fingerprint = command.fingerprint();
        var existing = results.find(command.articleId(), command.actor().userId(), command.operationId());
        if (existing == null) {
            try {
                var confirm = broker.send(broker.writeQueue(broker.partition(command.articleId())),
                        command.operationId(), command);
                if (!broker.confirmed(confirm, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))) {
                    throw CommentErrors.unavailable();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw CommentErrors.unavailable();
            } catch (org.springframework.amqp.AmqpException failure) {
                throw CommentErrors.unavailable();
            }
        } else {
            existing.requireFingerprint(fingerprint);
        }
    }

    public Status status(long article, UserActor actor, String suppliedOperation, String receipt) {
        String operation = CommentCommand.operation(suppliedOperation);
        var issued = tokens.open(receipt, Receipt.class, 1024);
        if (issued.articleId() != article || issued.actorId() != actor.userId()
                || !operation.equals(issued.operationId()) || issued.expiresAt() <= clock.millis()) {
            throw CommentErrors.invalid();
        }
        var result = results.find(article, actor.userId(), operation);
        if (result == null) return new Status(operation, "PENDING", null, null);
        result.requireFingerprint(issued.fingerprint());
        return new Status(operation, result.error() == null ? "SUCCEEDED" : "REJECTED",
                result.commentId() == null ? null : result.commentId().toString(), result.error());
    }

    record Receipt(long articleId, long actorId, String operationId, String fingerprint, long expiresAt) { }
    public record Accepted(String operationId, String receipt) { }
    public record Status(String operationId, String status, String commentId, String errorCode) { }
}
