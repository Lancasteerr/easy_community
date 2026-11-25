package com.febrie.backend.article.comment.infrastructure.messaging;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.application.command.CommentCommand;
import com.febrie.backend.article.search.infrastructure.messaging.IndexPublisher;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 两条持久化链路共用发布确认、退回检查与死信重放。 */
@Component
public class CommentBroker {
    public static final int PARTITIONS = 4;
    public static final int BATCH_SIZE = 20;

    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final String prefix;

    CommentBroker(@Qualifier("articleIndexRabbitTemplate") RabbitTemplate rabbit, ObjectMapper json,
                  @Value("${app.auth.redis-prefix}") String prefix) {
        this.rabbit = rabbit;
        this.json = json;
        this.prefix = prefix + ".comment";
    }

    public String writeQueue(int partition) { return prefix + ".write." + partition + ".q"; }
    String writeDeadQueue() { return prefix + ".write.dead.q"; }
    public String indexQueue() { return prefix + ".index.q"; }
    String indexDeadQueue() { return prefix + ".index.dead.q"; }

    public int partition(long article) {
        return Math.floorMod(Long.hashCode(article), PARTITIONS);
    }

    public CorrelationData send(String queue, String id, Object body) {
        var properties = new MessageProperties();
        properties.setMessageId(id);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return send(queue, new Message(json.writeValueAsBytes(body), properties));
    }

    private CorrelationData send(String queue, Message message) {
        var confirmation = new CorrelationData();
        rabbit.send("", queue, message, confirmation);
        return confirmation;
    }

    public <T> T decode(Message message, Class<T> type) {
        if (message.getBody().length > 8192) throw CommentErrors.invalid();
        return json.readValue(message.getBody(), type);
    }

    public boolean confirmed(CorrelationData confirmation, long deadline) throws InterruptedException {
        return IndexPublisher.awaitConfirmation(confirmation, deadline);
    }

    public int replay(boolean write, int limit) {
        String deadQueue = write ? writeDeadQueue() : indexDeadQueue();
        return rabbit.execute(channel -> {
            var converter = new DefaultMessagePropertiesConverter();
            int completed = 0;
            while (completed < limit) {
                var source = channel.basicGet(deadQueue, false);
                if (source == null) break;
                long tag = source.getEnvelope().getDeliveryTag();
                var message = new Message(source.getBody(), converter.toMessageProperties(
                        source.getProps(), source.getEnvelope(), "UTF-8"));
                try {
                    String queue = write
                            ? writeQueue(partition(decode(message, CommentCommand.class).articleId()))
                            : indexQueue();
                    if (!confirmed(send(queue, message), System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                        throw new IllegalStateException("评论死信重放未获确认");
                    }
                    channel.basicAck(tag, false);
                    completed++;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    channel.basicNack(tag, false, true);
                    throw new IllegalStateException("评论死信重放中断", interrupted);
                } catch (java.io.IOException | RuntimeException failure) {
                    channel.basicNack(tag, false, true);
                    throw failure;
                }
            }
            return completed;
        });
    }
}
