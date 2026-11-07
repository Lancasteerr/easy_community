package com.febrie.backend.article.search.infrastructure.messaging;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;

/** 离线维护不启动监听；不依赖消息顺序或单活消费者保证正确性。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class IndexListenerConfiguration {
    @Bean SimpleMessageListenerContainer articleIndexListener(ConnectionFactory factory, SearchProperties p, IndexConsumer consumer) {
        var listener = new SimpleMessageListenerContainer(factory);
        listener.setQueueNames(p.name("work.q"));
        listener.setConcurrentConsumers(2);
        listener.setPrefetchCount(5);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setDefaultRequeueRejected(false);
        listener.setMessageListener((ChannelAwareMessageListener) consumer::consume);
        return listener;
    }
}
