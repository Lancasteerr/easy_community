package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.LikeRabbitConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 非Web运行保留显式发布客户端，但不得连接Broker、声明拓扑或启动消费者。 */
class LikeRuntimeModeTests {
    @Test void nonWebContextKeepsMessagingDormantWithoutFeatureSwitches() {
        var factory=mock(ConnectionFactory.class);
        var properties=new LikeProperties("ec:test:mode:like",Duration.ofSeconds(2),128,2,20,
                67108864,60,4,Duration.ofMillis(300),"none",-1,0);
        new ApplicationContextRunner().withUserConfiguration(LikeRabbitConfiguration.class)
                .withBean(LikeProperties.class,()->properties).withBean(ConnectionFactory.class,()->factory)
                .run(context->{
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean("likeRabbitTemplate",RabbitTemplate.class));
                    assertTrue(context.getBeansOfType(SimpleMessageListenerContainer.class).isEmpty());
                    assertTrue(context.getBeansOfType(Declarables.class).isEmpty());
                    verify(factory,never()).createConnection();
                });
    }
}
