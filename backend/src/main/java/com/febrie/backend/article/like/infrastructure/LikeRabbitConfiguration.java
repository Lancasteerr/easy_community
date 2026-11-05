package com.febrie.backend.article.like.infrastructure;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** 显式Quorum拓扑；死信安全配置不可省略，命名空间隔离部署与测试。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeRabbitConfiguration {
    @Bean Declarables likeTopology(LikeProperties p) {
        var commands = new DirectExchange(p.name("command.x"), true, false);
        var dead = new DirectExchange(p.name("dead.x"), true, false);
        var work = new Queue(p.name("command.q"), true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-overflow", "reject-publish", "x-max-length-bytes", p.queueBytes(),
                "x-delivery-limit", 20, "x-dead-letter-exchange", dead.getName(),
                "x-dead-letter-routing-key", "article.like.dead", "x-dead-letter-strategy", "at-least-once"));
        var parked = new Queue(p.name("dead.q"), true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-overflow", "reject-publish", "x-max-length-bytes", p.queueBytes(), "x-delivery-limit", -1));
        return new Declarables(commands, dead, work, parked,
                BindingBuilder.bind(work).to(commands).with("article.like.set"),
                BindingBuilder.bind(parked).to(dead).with("article.like.dead"));
    }

    @Bean RabbitTemplate likeRabbitTemplate(ConnectionFactory factory) {
        var template = new RabbitTemplate(factory);
        template.setMandatory(true);
        return template;
    }
}
