package com.febrie.backend.article.comment.infrastructure.search;

import com.febrie.backend.article.comment.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** SQL 认领结束后才发消息或访问 ES，所有确认均允许幂等重放。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.comment.index-sync", havingValue = "true", matchIfMissing = true)
public class CommentIndexWorker {
    private static final Logger log = LoggerFactory.getLogger(CommentIndexWorker.class);
    private final CommentIndexTasks tasks;
    private final CommentIndex index;
    private final CommentBroker broker;

    CommentIndexWorker(CommentIndexTasks tasks, CommentIndex index, CommentBroker broker) {
        this.tasks = tasks;
        this.index = index;
        this.broker = broker;
    }

    @Scheduled(scheduler = "commentIndexScheduler", fixedDelay = 1000)
    public void relay() throws InterruptedException {
        var claim = tasks.claimLifecycle();
        var sent = new LinkedHashMap<CommentIndexTasks.Event, CorrelationData>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var event : claim.events()) {
            try {
                sent.put(event, broker.send(broker.indexQueue(), Long.toString(event.id()), event));
            } catch (RuntimeException failure) {
                log.warn("评论生命周期投递失败 eventId={}", event.id(), failure);
                tasks.finishLifecycle(event, claim.token(), false);
            }
        }
        for (var item : sent.entrySet()) {
            tasks.finishLifecycle(item.getKey(), claim.token(), broker.confirmed(item.getValue(), deadline));
        }
    }

    public void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        CommentIndexTasks.Event event;
        try {
            event = broker.decode(message, CommentIndexTasks.Event.class);
            if (event.id() <= 0 || event.commentId() <= 0 || event.version() <= 0) throw CommentErrors.invalid();
        } catch (RuntimeException invalid) {
            channel.basicNack(tag, false, false);
            return;
        }
        boolean written;
        try {
            var documents = tasks.load(List.of(event.commentId()));
            written = !documents.isEmpty() && documents.getFirst().indexVersion() >= event.version()
                    && index.write(documents).contains(event.commentId());
        } catch (IOException | RuntimeException failure) {
            log.warn("评论生命周期索引失败 eventId={}", event.id(), failure);
            written = false;
        }
        if (written) {
            channel.basicAck(tag, false);
        } else {
            channel.basicNack(tag, false, true);
        }
    }

    @Scheduled(scheduler = "commentIndexScheduler", fixedDelay = 1000)
    public void syncLikes() {
        var claim = tasks.claimLikes();
        if (claim.events().isEmpty()) return;
        Set<Long> successful;
        try {
            var ids = claim.events().stream().map(CommentIndexTasks.Event::commentId).toList();
            successful = index.write(tasks.load(ids));
        } catch (IOException | RuntimeException failure) {
            log.warn("评论点赞批量索引失败", failure);
            successful = Set.of();
        }
        for (var event : claim.events()) {
            tasks.finishLike(event, claim.token(), successful.contains(event.commentId()));
        }
    }
}
