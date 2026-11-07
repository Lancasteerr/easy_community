package com.febrie.backend.article.search.infrastructure.messaging;

import com.febrie.backend.article.search.infrastructure.config.SearchProperties;
import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.*;

/** 独立索引路由，不与点赞争抢消息；离线命令不隐式声明拓扑。 */
@Configuration(proxyBeanMethods = false)
public class IndexRabbitConfiguration {
    @Bean RabbitTemplate articleIndexRabbitTemplate(ConnectionFactory factory) {
        var rabbit = new RabbitTemplate(factory);
        rabbit.setMandatory(true);
        return rabbit;
    }
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    Declarables articleIndexTopology(SearchProperties p) { return topology(p); }

    public static Declarables topology(SearchProperties p) {
        var changes = new DirectExchange(p.name("change.x"), true, false);
        var dead = new DirectExchange(p.name("dead.x"), true, false);
        var work = new Queue(p.name("work.q"), true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-overflow", "reject-publish", "x-max-length-bytes", 67108864L,
                "x-delivery-limit", 20, "x-dead-letter-exchange", dead.getName(),
                "x-dead-letter-routing-key", "article.index.dead", "x-dead-letter-strategy", "at-least-once"));
        var parked = new Queue(p.name("dead.q"), true, false, false, Map.of(
                "x-queue-type", "quorum", "x-quorum-initial-group-size", 1,
                "x-overflow", "reject-publish", "x-max-length-bytes", 67108864L, "x-delivery-limit", -1));
        return new Declarables(changes, dead, work, parked,
                BindingBuilder.bind(work).to(changes).with("article.index.changed"),
                BindingBuilder.bind(parked).to(dead).with("article.index.dead"));
    }
}
