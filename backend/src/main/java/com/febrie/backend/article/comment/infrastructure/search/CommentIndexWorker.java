package com.febrie.backend.article.comment.infrastructure.search;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
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
    private final int batchSize;

    CommentIndexWorker(CommentIndexTasks tasks, CommentIndex index, CommentBroker broker,
                       @Value("${app.comment.index-batch-size}") int batchSize) {
        this.tasks = tasks;
        this.index = index;
        this.broker = broker;
        this.batchSize = batchSize;
    }

    @Scheduled(scheduler = "commentIndexScheduler", fixedDelay = 1000)
    public void relay() throws InterruptedException {
        var claim = tasks.claimLifecycle(batchSize);
        if (claim.events().isEmpty()) return;
        var sent = new LinkedHashMap<CommentIndexTasks.Event, CorrelationData>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var event : claim.events()) {
            try {
                sent.put(event, broker.send(broker.indexQueue(), Long.toString(event.id()), event));
            } catch (RuntimeException failure) {
                log.warn("评论生命周期投递失败 eventId={}", event.id(), failure);
            }
        }
        var confirmed = new HashSet<Long>();
        for (var item : sent.entrySet()) {
            if (broker.confirmed(item.getValue(), deadline)) confirmed.add(item.getKey().id());
        }
        tasks.finishLifecycle(claim, confirmed);
    }

    public void consume(List<Message> messages, Channel channel) throws IOException {
        var deliveries = new ArrayList<Delivery>();
        var minimumVersions = new LinkedHashMap<Long, Long>();
        for (var message : messages) {
            long tag = message.getMessageProperties().getDeliveryTag();
            CommentIndexTasks.Event event;
            try {
                event = broker.decode(message, CommentIndexTasks.Event.class);
                if (event.id() <= 0 || event.commentId() <= 0 || event.version() <= 0) throw CommentErrors.invalid();
            } catch (RuntimeException invalid) {
                channel.basicNack(tag, false, false);
                continue;
            }
            deliveries.add(new Delivery(tag, event));
            minimumVersions.merge(event.commentId(), event.version(), Math::min);
        }
        if (deliveries.isEmpty()) return;

        // 同一评论只写一次当前版本，各条消息仍按自己的版本和写入结果确认。
        var versions = new HashMap<Long, Long>();
        Set<Long> written;
        try {
            var documents = tasks.load(minimumVersions.keySet()).stream()
                    .filter(document -> document.indexVersion() >= minimumVersions.get(document.commentId()))
                    .toList();
            documents.forEach(document -> versions.put(document.commentId(), document.indexVersion()));
            written = documents.isEmpty() ? Set.of() : index.write(documents);
        } catch (IOException | RuntimeException failure) {
            log.warn("评论生命周期批量索引失败 size={}", deliveries.size(), failure);
            written = Set.of();
        }
        for (var delivery : deliveries) {
            var event = delivery.event();
            if (written.contains(event.commentId()) && versions.get(event.commentId()) >= event.version()) {
                channel.basicAck(delivery.tag(), false);
            } else {
                channel.basicNack(delivery.tag(), false, true);
            }
        }
    }

    private record Delivery(long tag, CommentIndexTasks.Event event) { }

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
