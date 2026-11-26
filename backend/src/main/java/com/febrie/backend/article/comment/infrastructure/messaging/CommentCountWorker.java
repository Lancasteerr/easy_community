package com.febrie.backend.article.comment.infrastructure.messaging;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.application.command.CommentCountProjection;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCountOutbox;
import com.febrie.backend.article.comment.infrastructure.redis.CommentMaintenance;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Outbox 只发布已提交的计数事件，分片单活保证同一文章的重算顺序。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CommentCountWorker {
    static final int BATCH_SIZE = 128;
    private static final Logger log = LoggerFactory.getLogger(CommentCountWorker.class);
    private final CommentCountOutbox outbox;
    private final CommentBroker broker;
    private final CommentCountProjection projection;
    private final CommentMaintenance maintenance;

    CommentCountWorker(CommentCountOutbox outbox, CommentBroker broker, CommentCountProjection projection,
                       CommentMaintenance maintenance) {
        this.outbox = outbox;
        this.broker = broker;
        this.projection = projection;
        this.maintenance = maintenance;
    }

    @Scheduled(scheduler = "commentCountScheduler", fixedDelay = 250)
    public void relay() throws InterruptedException {
        var claim = outbox.claim(BATCH_SIZE);
        if (claim.events().isEmpty()) return;
        var sent = new LinkedHashMap<Long, CorrelationData>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (var event : claim.events()) {
            try {
                String queue = broker.countQueue(broker.partition(event.articleId()));
                sent.put(event.id(), broker.send(queue, Long.toString(event.id()), event));
            } catch (RuntimeException failure) {
                log.warn("评论计数投递失败 eventId={}", event.id(), failure);
            }
        }
        var confirmed = new HashSet<Long>();
        for (var item : sent.entrySet()) {
            if (broker.confirmed(item.getValue(), deadline)) confirmed.add(item.getKey());
        }
        outbox.finish(claim, confirmed);
    }

    void consume(int partition, List<Message> messages, Channel channel) throws IOException {
        var articles = new LinkedHashSet<Long>();
        var tags = new ArrayList<Long>();
        for (var message : messages) {
            long tag = message.getMessageProperties().getDeliveryTag();
            CommentCountOutbox.Event event;
            try {
                event = broker.decode(message, CommentCountOutbox.Event.class);
                if (event.id() <= 0 || event.articleId() <= 0 || broker.partition(event.articleId()) != partition) {
                    throw CommentErrors.invalid();
                }
            } catch (RuntimeException invalid) {
                channel.basicNack(tag, false, false);
                continue;
            }
            articles.add(event.articleId());
            tags.add(tag);
        }
        if (tags.isEmpty()) return;

        try {
            projection.recount(articles);
        } catch (RuntimeException failure) {
            log.warn("评论计数批次失败 partition={} articles={}", partition, articles.size(), failure);
            for (long tag : tags) channel.basicNack(tag, false, true);
            return;
        }
        // 重算事务已提交，随后清除旧缓存，让读请求取到本批结果。
        articles.forEach(maintenance::count);
        for (long tag : tags) channel.basicAck(tag, false);
    }
}
