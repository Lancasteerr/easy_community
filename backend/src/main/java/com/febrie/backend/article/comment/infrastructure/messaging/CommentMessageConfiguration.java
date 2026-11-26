package com.febrie.backend.article.comment.infrastructure.messaging;

import com.febrie.backend.article.comment.infrastructure.search.CommentIndexWorker;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareBatchMessageListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 写分片与 ES 投影独立限流及死信，楼层刷新继续使用现有队列。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CommentMessageConfiguration {
    @Bean
    Declarables commentBusinessQueues(CommentBroker broker) {
        var queues = new ArrayList<Declarable>();
        queues.add(dead(broker.writeDeadQueue()));
        queues.add(dead(broker.countDeadQueue()));
        queues.add(dead(broker.indexDeadQueue()));
        queues.add(work(broker.indexQueue(), broker.indexDeadQueue(), false));
        for (int partition = 0; partition < CommentBroker.PARTITIONS; partition++) {
            queues.add(work(broker.writeQueue(partition), broker.writeDeadQueue(), true));
            queues.add(work(broker.countQueue(partition), broker.countDeadQueue(), true));
        }
        return new Declarables(queues);
    }

    @Bean
    ThreadPoolTaskExecutor commentArticleExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CommentBroker.PARTITIONS);
        executor.setMaxPoolSize(CommentBroker.PARTITIONS);
        executor.setQueueCapacity(CommentBroker.PARTITIONS * CommentBroker.BATCH_SIZE);
        executor.setThreadNamePrefix("comment-article-");
        return executor;
    }

    @Bean
    WriteListeners commentWriteListeners(ConnectionFactory factory, CommentBroker broker, CommentCommandConsumer consumer) {
        var listeners = new ArrayList<SimpleMessageListenerContainer>();
        for (int index = 0; index < CommentBroker.PARTITIONS; index++) {
            int partition = index;
            var listener = new SimpleMessageListenerContainer(factory);
            listener.setBeanName("commentWrite-" + partition);
            listener.setQueueNames(broker.writeQueue(partition));
            listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            listener.setPrefetchCount(CommentBroker.BATCH_SIZE);
            listener.setConsumerBatchEnabled(true);
            listener.setBatchSize(CommentBroker.BATCH_SIZE);
            listener.setReceiveTimeout(100);
            listener.setBatchReceiveTimeout(100);
            listener.setForceStop(true);
            listener.setMessageListener((ChannelAwareBatchMessageListener)
                    (messages, channel) -> {
                        try {
                            consumer.consume(partition, messages, channel);
                        } catch (java.io.IOException failure) {
                            throw new org.springframework.amqp.ImmediateRequeueAmqpException("评论确认失败", failure);
                        }
                    });
            listeners.add(listener);
        }
        return new WriteListeners(List.copyOf(listeners));
    }

    @Bean
    ThreadPoolTaskScheduler commentIndexScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("comment-index-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskScheduler commentCountScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("comment-count-");
        return scheduler;
    }

    @Bean
    WriteListeners commentCountListeners(ConnectionFactory factory, CommentBroker broker, CommentCountWorker worker) {
        var listeners = new ArrayList<SimpleMessageListenerContainer>();
        for (int index = 0; index < CommentBroker.PARTITIONS; index++) {
            int partition = index;
            var listener = batchListener(factory, broker.countQueue(partition), CommentCountWorker.BATCH_SIZE);
            listener.setBeanName("commentCount-" + partition);
            listener.setMessageListener((ChannelAwareBatchMessageListener) (messages, channel) -> {
                try {
                    worker.consume(partition, messages, channel);
                } catch (IOException failure) {
                    throw new ImmediateRequeueAmqpException("评论计数确认失败", failure);
                }
            });
            listeners.add(listener);
        }
        return new WriteListeners(List.copyOf(listeners));
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "app.comment.index-sync", havingValue = "true", matchIfMissing = true)
    SimpleMessageListenerContainer commentIndexListener(ConnectionFactory factory, CommentBroker broker,
                                                         CommentIndexWorker worker,
                                                         @Value("${app.comment.index-batch-size}") int batchSize) {
        // 保留 relay 的投递节奏，消费端以短窗口收集消息并合并 ES 写入。
        var listener = batchListener(factory, broker.indexQueue(), batchSize);
        listener.setMessageListener((ChannelAwareBatchMessageListener) (messages, channel) -> {
            try {
                worker.consume(messages, channel);
            } catch (IOException failure) {
                throw new ImmediateRequeueAmqpException("评论索引确认失败", failure);
            }
        });
        return listener;
    }

    private static SimpleMessageListenerContainer batchListener(ConnectionFactory factory, String queue, int batchSize) {
        var listener = new SimpleMessageListenerContainer(factory);
        listener.setQueueNames(queue);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setPrefetchCount(batchSize);
        listener.setBatchSize(batchSize);
        listener.setConsumerBatchEnabled(true);
        listener.setReceiveTimeout(100);
        listener.setBatchReceiveTimeout(100);
        listener.setForceStop(true);
        return listener;
    }

    private static Queue work(String name, String parked, boolean single) {
        return new Queue(name, true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-single-active-consumer", single, "x-overflow", "reject-publish",
                "x-max-length-bytes", 67108864L, "x-delivery-limit", 20,
                "x-dead-letter-exchange", "", "x-dead-letter-routing-key", parked,
                "x-dead-letter-strategy", "at-least-once"));
    }

    private static Queue dead(String name) {
        return new Queue(name, true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-overflow", "reject-publish", "x-delivery-limit", -1));
    }

    record WriteListeners(List<SimpleMessageListenerContainer> listeners)
            implements SmartLifecycle, InitializingBean, DisposableBean {
        @Override public void afterPropertiesSet() { listeners.forEach(SimpleMessageListenerContainer::afterPropertiesSet); }
        @Override public boolean isRunning() { return listeners.stream().allMatch(SimpleMessageListenerContainer::isRunning); }
        @Override public void start() { listeners.forEach(SimpleMessageListenerContainer::start); }
        @Override public void stop() { listeners.forEach(SimpleMessageListenerContainer::stop); }
        @Override public void stop(Runnable callback) {
            var remaining = new AtomicInteger(listeners.size());
            listeners.forEach(listener -> listener.stop(() -> {
                if (remaining.decrementAndGet() == 0) callback.run();
            }));
        }
        @Override public void destroy() { listeners.forEach(SimpleMessageListenerContainer::destroy); }
    }
}
