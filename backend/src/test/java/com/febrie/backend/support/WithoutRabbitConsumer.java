package com.febrie.backend.support;

import java.lang.annotation.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 非MQ的Web集成测试仍装配完整点赞业务，只替换监听容器并禁止自动声明测试拓扑。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(name = "likeListener", types = SimpleMessageListenerContainer.class)
@TestPropertySource(properties = "spring.rabbitmq.dynamic=false")
public @interface WithoutRabbitConsumer { }
