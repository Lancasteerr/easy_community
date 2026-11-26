package com.febrie.backend.support;

import com.febrie.backend.article.like.infrastructure.messaging.LikeListenerGroup;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 非 MQ 的 Web 集成测试保留业务入口，隔离消费监听器和索引调度。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(name = "likeListener", types = LikeListenerGroup.class)
@MockitoBean(name = "commentRefreshListener", types = SimpleMessageListenerContainer.class)
@WithoutSearchIndexing
@TestPropertySource(properties = {"spring.rabbitmq.dynamic=false", "app.comment.index-sync=false", "app.comment.count-sync=false"})
public @interface WithoutRabbitConsumer { }
