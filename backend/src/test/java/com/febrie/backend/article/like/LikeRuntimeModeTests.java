package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.LikeProjection;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.*;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 维护进程保持消息组件静默，Web实例统一装配消费线程池。 */
class LikeRuntimeModeTests {
    @Test void nonWebContextKeepsMessagingDormantWithoutFeatureSwitches() {
        var factory=mock(ConnectionFactory.class);
        new ApplicationContextRunner().withUserConfiguration(LikeRabbitConfiguration.class)
                .withBean(LikeProperties.class, () -> properties("none")).withBean(ConnectionFactory.class, () -> factory)
                .run(context->{
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean("likeRabbitTemplate",RabbitTemplate.class));
                    assertTrue(context.getBeansOfType(SimpleMessageListenerContainer.class).isEmpty());
                    assertTrue(context.getBeansOfType(LikeListenerGroup.class).isEmpty());
                    assertTrue(context.getBeansOfType(LikeArticleExecutor.class).isEmpty());
                    assertTrue(context.getBeansOfType(Declarables.class).isEmpty());
                    verify(factory,never()).createConnection();
                });
    }

    @Test void webContextWiresOneArticleExecutorForAllPartitions() {
        var factory = mock(ConnectionFactory.class);
        // 用维护模式关闭容器自启动，只核验真实Web配置的依赖装配。
        new WebApplicationContextRunner().withUserConfiguration(LikeRabbitConfiguration.class)
                .withBean(LikeProperties.class, () -> properties("status"))
                .withBean(ConnectionFactory.class, () -> factory)
                .withBean(LikeCodec.class, () -> mock(LikeCodec.class))
                .withBean(LikeSqlService.class, () -> mock(LikeSqlService.class))
                .withBean(LikeProjection.class, () -> mock(LikeProjection.class))
                .withBean(LikeTelemetry.class, () -> mock(LikeTelemetry.class))
                .withBean(LikePartitionRouter.class, () -> new LikePartitionRouter(properties("status")))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(LikeArticleExecutor.class).size());
                    assertNotNull(context.getBean(LikeConsumer.class));
                    assertNotNull(context.getBean(LikeListenerGroup.class));
                    verify(factory, never()).createConnection();
                });
    }

    private static LikeProperties properties(String mode) {
        return new LikeProperties("ec:test:mode:like", Duration.ofSeconds(2), 128, 4, 20,
                67108864, 60, Duration.ofMillis(300), mode, -1, 0, 20, Duration.ofMillis(100));
    }
}
