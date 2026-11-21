package com.febrie.backend.article.like.infrastructure.messaging;

import com.febrie.backend.article.like.application.LikeProjection;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;

/** 固定分区Quorum拓扑；共用死信队列，不改变已受理消息的版本。 */
@Configuration(proxyBeanMethods = false)
public class LikeRabbitConfiguration {
    @Bean RabbitTemplate likeRabbitTemplate(ConnectionFactory factory) {
        var template = new RabbitTemplate(factory);
        template.setMandatory(true);
        return template;
    }

    /** 非Web维护命令保留发布客户端，但不创建监听组或隐式声明拓扑。 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class WebMessaging {
        @Bean LikeArticleExecutor likeArticleExecutor(LikeTelemetry telemetry) {
            return new LikeArticleExecutor(telemetry);
        }
        @Bean LikeConsumer likeConsumer(LikeCodec codec, LikeSqlService sql, LikeProjection projection,
                                        LikeTelemetry telemetry, LikeProperties p, LikeArticleExecutor executor) {
            return new LikeConsumer(codec, sql, projection, telemetry, p, executor);
        }
        @Bean LikeListenerGroup likeListener(ConnectionFactory factory, LikeProperties p,
                                             LikePartitionRouter router, LikeConsumer consumer) {
            return new LikeListenerGroup(factory, p, router, consumer);
        }
        @Bean Declarables likeTopology(LikeProperties p) { return topology(p); }
    }

    /** 运维与轻量Broker测试也能复用相同的声明规则。 */
    public static Declarables topology(LikeProperties p) {
        if (p.maintaining()) { return new Declarables(); }
        var router = new LikePartitionRouter(p);
        var commands = new DirectExchange(p.name("command.x"), true, false);
        var dead = new DirectExchange(p.name("dead.x"), true, false);
        var parked = new Queue(p.name("dead.q"), true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-overflow", "reject-publish", "x-max-length-bytes", p.queueBytes(), "x-delivery-limit", -1));
        var declarations = new ArrayList<Declarable>();
        declarations.add(commands); declarations.add(dead); declarations.add(parked);
        declarations.add(BindingBuilder.bind(parked).to(dead).with("article.like.dead"));
        for (int partition = 0; partition < p.partitionCount(); partition++) {
            var work = new Queue(router.queue(partition), true, false, false, Map.of(
                    "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                    "x-single-active-consumer", true,
                    "x-overflow", "reject-publish", "x-max-length-bytes", p.queueBytes(),
                    "x-delivery-limit", 20, "x-dead-letter-exchange", dead.getName(),
                    "x-dead-letter-routing-key", "article.like.dead", "x-dead-letter-strategy", "at-least-once"));
            declarations.add(work);
            declarations.add(BindingBuilder.bind(work).to(commands).with(router.routingKey(partition)));
        }
        return new Declarables(declarations);
    }
}
