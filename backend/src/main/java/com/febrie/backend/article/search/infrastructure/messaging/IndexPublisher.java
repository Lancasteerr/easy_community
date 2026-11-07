package com.febrie.backend.article.search.infrastructure.messaging;

import com.febrie.backend.article.search.application.IndexEvent;
import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.util.concurrent.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 先发整批、后收确认；同一发送关联Confirm与Return，不把Broker接收当作消费完成。 */
@Component
public class IndexPublisher {
    private final RabbitTemplate rabbit;
    private final SearchProperties p;
    private final ObjectMapper json;
    public IndexPublisher(@Qualifier("articleIndexRabbitTemplate") RabbitTemplate rabbit, SearchProperties p, ObjectMapper json) {
        this.rabbit = rabbit; this.p = p; this.json = json;
    }
    public CorrelationData send(IndexEvent event) {
        var metadata = new MessageProperties();
        metadata.setMessageId(Long.toString(event.eventId()));
        metadata.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        metadata.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        return send(new Message(json.writeValueAsBytes(event), metadata));
    }
    public CorrelationData send(Message message) {
        var correlation = new CorrelationData();
        rabbit.send(p.name("change.x"), "article.index.changed", message, correlation);
        return correlation;
    }
    public boolean confirmed(CorrelationData correlation, long deadlineNanos) throws InterruptedException {
        try {
            // 已完成确认即使排在批次末尾，也不因为前项超时而丢弃确定成功结果。
            var future = correlation.getFuture();
            var result = future.isDone() ? future.get() : future.get(Math.max(1, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            return result.ack() && correlation.getReturned() == null;
        } catch (ExecutionException | TimeoutException failure) { return false; }
    }
}
