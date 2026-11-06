package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 仅离线命令显式调用；确认重发成功后才ACK原死信，未知结果保留原消息。 */
@Component
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeDeadLetterReplay {
    private final RabbitTemplate rabbit;
    private final LikePublisher publisher;
    private final LikeCodec codec;
    private final LikeProperties properties;
    public LikeDeadLetterReplay(@Qualifier("likeRabbitTemplate") RabbitTemplate rabbit, LikePublisher publisher,
                                LikeCodec codec, LikeProperties properties) {
        this.rabbit = rabbit; this.publisher = publisher; this.codec = codec; this.properties = properties;
    }

    public int replay(int limit) {
        if (limit < 1 || limit > 10000) { throw new IllegalArgumentException("重放数量必须为1至10000"); }
        return rabbit.execute(channel -> {
            var converter = new DefaultMessagePropertiesConverter();
            int completed = 0;
            while (completed < limit) {
                var source = channel.basicGet(properties.name("dead.q"), false);
                if (source == null) { break; }
                long tag = source.getEnvelope().getDeliveryTag();
                try {
                    codec.decode(source.getBody());
                    var metadata = converter.toMessageProperties(source.getProps(), source.getEnvelope(), "UTF-8");
                    publisher.publish(new Message(source.getBody(), metadata));
                    channel.basicAck(tag, false);
                    completed++;
                } catch (RuntimeException | java.io.IOException failure) {
                    channel.basicNack(tag, false, true);
                    throw failure;
                }
            }
            return completed;
        });
    }
}
