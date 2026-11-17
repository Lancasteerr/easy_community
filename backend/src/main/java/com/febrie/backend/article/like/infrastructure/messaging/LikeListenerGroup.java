package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.SmartLifecycle;

/** 一组分区容器共享生命周期；每个容器只消费一个队列，备用实例由Broker单活控制。 */
public class LikeListenerGroup implements SmartLifecycle, InitializingBean, DisposableBean {
    private final List<SimpleMessageListenerContainer> listeners;
    private final boolean autoStartup;

    public LikeListenerGroup(ConnectionFactory factory, LikeProperties properties,
                             LikePartitionRouter router, LikeConsumer consumer) {
        autoStartup = !properties.maintaining();
        List<SimpleMessageListenerContainer> containers = new ArrayList<>();
        for (String queue : router.queues()) {
            var listener = new SimpleMessageListenerContainer(factory);
            listener.setQueueNames(queue);
            listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            listener.setConcurrentConsumers(1);
            listener.setMaxConcurrentConsumers(1);
            listener.setPrefetchCount(properties.prefetch());
            listener.setDefaultRequeueRejected(false);
            listener.setAutoStartup(false);
            listener.setMessageListener((ChannelAwareMessageListener) consumer::consume);
            containers.add(listener);
        }
        listeners = List.copyOf(containers);
    }

    @Override public void afterPropertiesSet() { listeners.forEach(SimpleMessageListenerContainer::afterPropertiesSet); }
    @Override public boolean isAutoStartup() { return autoStartup; }
    @Override public boolean isRunning() { return listeners.stream().allMatch(SimpleMessageListenerContainer::isRunning); }
    @Override public void start() { listeners.forEach(SimpleMessageListenerContainer::start); }
    @Override public void stop() { listeners.forEach(SimpleMessageListenerContainer::stop); }
    /** Spring关闭时等待所有分区完成停止，避免先销毁数据源再出现迟到消费。 */
    @Override public void stop(Runnable callback) {
        var remaining = new AtomicInteger(listeners.size());
        listeners.forEach(listener -> listener.stop(() -> { if (remaining.decrementAndGet() == 0) { callback.run(); } }));
    }
    @Override public void destroy() { listeners.forEach(SimpleMessageListenerContainer::destroy); }
}
