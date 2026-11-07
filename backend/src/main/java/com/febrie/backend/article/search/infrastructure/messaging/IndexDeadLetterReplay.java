package com.febrie.backend.article.search.infrastructure.messaging;

import com.febrie.backend.article.search.application.IndexEvent;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 先确认重新发布成功，再ACK原死信；未知结果不丢原始消息。 */
@Component
public class IndexDeadLetterReplay {
    private final RabbitTemplate rabbit;
    private final IndexPublisher publisher;
    private final SearchProperties p;
    private final ObjectMapper json;
    public IndexDeadLetterReplay(@Qualifier("articleIndexRabbitTemplate") RabbitTemplate rabbit, IndexPublisher publisher, SearchProperties p, ObjectMapper json) {
        this.rabbit = rabbit; this.publisher = publisher; this.p = p; this.json = json;
    }
    public int replay(int limit) {
        if (limit < 1 || limit > 10000) { throw new IllegalArgumentException("重放数量必须为1至10000"); }
        return rabbit.execute(channel -> {
            var converter = new DefaultMessagePropertiesConverter(); int completed = 0;
            while (completed < limit) {
                var source = channel.basicGet(p.name("dead.q"), false);
                if (source == null) { break; }
                long tag = source.getEnvelope().getDeliveryTag();
                try {
                    json.readValue(source.getBody(), IndexEvent.class);
                    var message = new Message(source.getBody(), converter.toMessageProperties(source.getProps(), source.getEnvelope(), "UTF-8"));
                    if (!publisher.confirmed(publisher.send(message), System.nanoTime() + TimeUnit.SECONDS.toNanos(5))) {
                        throw new IllegalStateException("死信重放未确认成功");
                    }
                    channel.basicAck(tag, false); completed++;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); channel.basicNack(tag, false, true);
                    throw new IllegalStateException("死信重放中断", interrupted);
                } catch (RuntimeException | java.io.IOException failure) { channel.basicNack(tag, false, true); throw failure; }
            }
            return completed;
        });
    }
}
