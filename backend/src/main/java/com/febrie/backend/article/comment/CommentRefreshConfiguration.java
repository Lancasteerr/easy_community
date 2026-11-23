package com.febrie.backend.article.comment;

import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 评论刷新拓扑随正常 Web 进程注册，离线维护命令不启动监听。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommentRefreshConfiguration {
    @Bean Queue commentRefreshQueue(@Value("${app.comment.refresh-queue}") String queue) {
        return new Queue(queue, true, false, false,
                Map.of("x-max-length", 10000, "x-overflow", "reject-publish", "x-message-ttl", 30000));
    }

    @Bean RabbitTemplate commentRefreshRabbit(ConnectionFactory factory) {
        var template = new RabbitTemplate(factory);
        template.setMandatory(true);
        return template;
    }

    @Bean ThreadPoolTaskExecutor commentRefreshPublishExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("comment-refresh-publish-");
        return executor;
    }

    @Bean SimpleMessageListenerContainer commentRefreshListener(ConnectionFactory factory, CommentRefreshes refreshes,
                                                                @Value("${app.comment.refresh-queue}") String queue) {
        var listener = new SimpleMessageListenerContainer(factory);
        listener.setQueueNames(queue);
        listener.setTaskExecutor(new SimpleAsyncTaskExecutor("comment-refresh-consume-"));
        listener.setAutoStartup(true);
        listener.setConcurrentConsumers(2);
        listener.setPrefetchCount(1);
        listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setDefaultRequeueRejected(false);
        listener.setMessageListener((ChannelAwareMessageListener) refreshes::consume);
        return listener;
    }

    @Bean ThreadPoolTaskScheduler commentMaintenanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("comment-maintenance-");
        return scheduler;
    }
}
