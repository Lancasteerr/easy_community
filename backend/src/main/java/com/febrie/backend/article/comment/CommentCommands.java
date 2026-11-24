package com.febrie.backend.article.comment;

import com.febrie.backend.user.application.dto.UserActor;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** 受理凭证由会话主体及摘要签名，允许在消费者写结果前查询待处理状态。 */
@Service
class CommentCommands {
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

    Accepted submit(CommentCommand command) {
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
            requireFingerprint(existing, fingerprint);
        }
        var receipt = new Receipt(command.articleId(), command.actor().userId(), command.operationId(), fingerprint,
                clock.millis() + Duration.ofDays(1).toMillis());
        return new Accepted(command.operationId(), tokens.seal(receipt));
    }

    Status status(long article, UserActor actor, String suppliedOperation, String receipt) {
        String operation = CommentCommand.operation(suppliedOperation);
        var issued = tokens.open(receipt, Receipt.class, 1024);
        if (issued.articleId() != article || issued.actorId() != actor.userId()
                || !operation.equals(issued.operationId()) || issued.expiresAt() <= clock.millis()) {
            throw CommentErrors.invalid();
        }
        var result = results.find(article, actor.userId(), operation);
        if (result == null) return new Status(operation, "PENDING", null, null);
        requireFingerprint(result, issued.fingerprint());
        return new Status(operation, result.error() == null ? "SUCCEEDED" : "REJECTED",
                result.commentId() == null ? null : result.commentId().toString(), result.error());
    }

    private static void requireFingerprint(CommentCommandResults.Result result, String fingerprint) {
        if (!result.fingerprint().equals(fingerprint)) throw CommentErrors.operationConflict();
    }

    record Receipt(long articleId, long actorId, String operationId, String fingerprint, long expiresAt) { }
    record Accepted(String operationId, String receipt) { }
    record Status(String operationId, String status, String commentId, String errorCode) { }
}
