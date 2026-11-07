package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.IndexEvent;
import com.febrie.backend.article.search.infrastructure.messaging.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 专用Broker随机拓扑中的真实ACK+Return及重复发送，不连接开发vhost。 */
@EnabledIfEnvironmentVariable(named = "IT_ARTICLE", matches = "true")
class IndexPublisherIntegrationTests {
    @Test void confirmsRoutingAndAllowsDuplicateDelivery() throws Exception {
        var p = SearchTestSupport.properties(Map.of("app.search.namespace", "ec:test:" + UUID.randomUUID() + ":search"));
        var factory = new CachingConnectionFactory(System.getenv().getOrDefault("IT_RABBITMQ_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("IT_RABBITMQ_PORT", "17772")));
        factory.setUsername(System.getenv("IT_RABBITMQ_USERNAME"));
        factory.setPassword(System.getenv("IT_RABBITMQ_PASSWORD"));
        factory.setVirtualHost(System.getenv("IT_RABBITMQ_VHOST"));
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        var admin = new RabbitAdmin(factory);
        var topology = IndexRabbitConfiguration.topology(p).getDeclarables();
        try {
            topology.stream().filter(Exchange.class::isInstance).map(Exchange.class::cast).forEach(admin::declareExchange);
            topology.stream().filter(org.springframework.amqp.core.Queue.class::isInstance).map(org.springframework.amqp.core.Queue.class::cast).forEach(admin::declareQueue);
            topology.stream().filter(Binding.class::isInstance).map(Binding.class::cast).forEach(admin::declareBinding);
            var rabbit = new RabbitTemplate(factory); rabbit.setMandatory(true);
            var publisher = new IndexPublisher(rabbit, p, new ObjectMapper());
            var event = new IndexEvent(1, 2, 3);
            var first = publisher.send(event); var duplicate = publisher.send(event);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            assertTrue(publisher.confirmed(first, deadline)); assertTrue(publisher.confirmed(duplicate, deadline));
            assertNotNull(rabbit.receive(p.name("work.q"), 3000));
            assertNotNull(rabbit.receive(p.name("work.q"), 3000));
            var binding = topology.stream().filter(Binding.class::isInstance).map(Binding.class::cast)
                    .filter(b -> b.getRoutingKey().equals("article.index.changed")).findFirst().orElseThrow();
            admin.removeBinding(binding);
            var returned = publisher.send(event);
            assertFalse(publisher.confirmed(returned, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)));
            assertNotNull(returned.getReturned());
        } finally {
            admin.deleteQueue(p.name("work.q")); admin.deleteQueue(p.name("dead.q"));
            admin.deleteExchange(p.name("change.x")); admin.deleteExchange(p.name("dead.x"));
            factory.destroy();
        }
    }
}
