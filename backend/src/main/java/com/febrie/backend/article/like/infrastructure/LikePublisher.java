package com.febrie.backend.article.like.infrastructure;

import com.febrie.backend.article.like.application.LikeErrors;
import com.febrie.backend.article.like.domain.LikeCommand;
import java.util.Date;
import java.util.concurrent.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 只等待同次发送的确认，不创建后台重发任务；未知结果保留未知语义。 */
@Component
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikePublisher {
    private final RabbitTemplate rabbit;
    private final LikeProperties properties;
    private final LikeCodec codec;
    private final LikeTelemetry telemetry;
    private final Semaphore pending;
    public LikePublisher(@Qualifier("likeRabbitTemplate") RabbitTemplate rabbit, LikeProperties properties,
                         LikeCodec codec, LikeTelemetry telemetry) {
        this.rabbit = rabbit; this.properties = properties; this.codec = codec; this.telemetry = telemetry;
        this.pending = new Semaphore(properties.pendingLimit());
    }

    public void publish(LikeCommand command) { publish(message(command)); }

    /** 死信重放保留原始body和messageId；仅更换投递目标，不重新分配版本。 */
    public void publish(Message message) {
        if (!pending.tryAcquire()) { throw LikeErrors.publishUnavailable(); }
        long started = System.nanoTime();
        try {
            // 预连接失败可证明尚未开始发送；send开始后的异常一律按结果未知处理。
            try (Connection ignored = rabbit.getConnectionFactory().createConnection()) { }
            catch (RuntimeException error) { telemetry.event("publish", "unavailable"); throw LikeErrors.publishUnavailable(); }
            var correlation = new CorrelationData();
            try {
                rabbit.send(properties.name("command.x"), "article.like.set", message, correlation);
                var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (correlation.getReturned() != null) { telemetry.event("publish", "returned"); throw LikeErrors.rejected(); }
                if (!confirm.ack()) { telemetry.event("publish", "nack"); throw LikeErrors.unknown(); }
                telemetry.event("publish", "confirmed");
            } catch (com.febrie.backend.shared.error.ApiException known) { throw known; }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); telemetry.event("publish", "unknown"); throw LikeErrors.unknown();
            } catch (Exception unknown) { telemetry.event("publish", "unknown"); throw LikeErrors.unknown(); }
        } finally { pending.release(); telemetry.time("confirm", started); }
    }

    private Message message(LikeCommand command) {
        var metadata = new MessageProperties();
        metadata.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadata.setContentEncoding("UTF-8");
        metadata.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        metadata.setMessageId(command.messageId());
        metadata.setTimestamp(Date.from(command.occurredAt()));
        return new Message(codec.encode(command), metadata);
    }
}
