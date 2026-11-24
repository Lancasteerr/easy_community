package com.febrie.backend.article.comment;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** SWR 发布线程和 AMQP 消费线程都独立于 Servlet 请求执行。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommentRefreshes {
    private static final Logger log = LoggerFactory.getLogger(CommentRefreshes.class);
    private final RabbitTemplate rabbit;
    private final ThreadPoolTaskExecutor publishers;
    private final CommentSnapshotCache cache;
    private final CommentSnapshots snapshots;
    private final ObjectMapper json;
    private final Clock clock;
    private final String queue;
    private final MeterRegistry metrics;
    private final AtomicInteger queueDepth = new AtomicInteger(-1);

    public CommentRefreshes(@Qualifier("commentRefreshRabbit") RabbitTemplate rabbit,
                            @Qualifier("commentRefreshPublishExecutor") ThreadPoolTaskExecutor publishers,
                            CommentSnapshotCache cache, CommentSnapshots snapshots,
                            ObjectMapper json, Clock clock, MeterRegistry metrics,
                            @Value("${app.comment.refresh-queue}") String queue) {
        this.rabbit = rabbit;
        this.publishers = publishers;
        this.cache = cache;
        this.snapshots = snapshots;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.queue = queue;
        metrics.gauge("community.comment.refresh.queued", queueDepth);
    }

    void offer(CommentListKey key, String expected) {
        try {
            publishers.execute(() -> publish(key, expected));
        } catch (TaskRejectedException rejected) {
            // 使用拒绝策略而不是回退给调用线程，Servlet 不等待 Broker。
            snapshots.event("publish_saturated");
        }
    }

    private void publish(CommentListKey key, String expected) {
        try {
            var current = cache.current(key);
            if (current == null || !current.id().equals(expected)) return;
            String token = cache.claimRefresh(key, expected);
            if (token == null) return;

            var properties = new MessageProperties();
            properties.setMessageId(token);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            var command = new Refresh(key, expected, token, clock.millis());
            var confirmation = new CorrelationData();
            rabbit.send("", queue, new Message(json.writeValueAsBytes(command), properties), confirmation);
            if (!confirmation.getFuture().get(2, TimeUnit.SECONDS).ack() || confirmation.getReturned() != null) {
                snapshots.event("publish_failed");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            snapshots.event("publish_failed");
            log.debug("评论刷新消息发布失败 key={}", key, failure);
        }
    }

    void consume(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        Refresh command;
        try {
            if (message.getBody().length > 2048) throw new IllegalArgumentException("评论刷新消息过大");
            command = json.readValue(message.getBody(), Refresh.class);
            UUID.fromString(command.expected());
            UUID.fromString(command.token());
            if (command.key() == null || command.key().rootId() == 0 || command.queuedAt() < 0) throw new IllegalArgumentException("评论刷新消息无效");
        } catch (RuntimeException invalid) {
            snapshots.event("invalid_message");
            channel.basicReject(tag, false);
            return;
        }
        metrics.timer("community.comment.refresh.queue.age")
                .record(Math.max(0, clock.millis() - command.queuedAt()), TimeUnit.MILLISECONDS);
        try {
            snapshots.refresh(command.key(), command.expected());
        } catch (RuntimeException failure) {
            // 这是可重新产生的读触发任务；旧快照保留，避免失败消息反复重投。
            snapshots.event("refresh_failed");
            log.warn("评论列表刷新失败 key={}", command.key(), failure);
        }
        channel.basicAck(tag, false);
    }

    @Scheduled(scheduler = "commentMaintenanceScheduler", fixedDelay = 10000, initialDelay = 10000)
    public void measureQueue() {
        try {
            Integer ready = rabbit.execute(channel -> channel.queueDeclarePassive(queue).getMessageCount());
            queueDepth.set(ready == null ? -1 : ready);
        } catch (RuntimeException unavailable) {
            queueDepth.set(-1);
        }
    }

    record Refresh(CommentListKey key, String expected, String token, long queuedAt) { }
}
