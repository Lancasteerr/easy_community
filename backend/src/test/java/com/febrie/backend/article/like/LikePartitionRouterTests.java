package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.messaging.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定路由和真实声明参数是多实例单活的共同前提。 */
class LikePartitionRouterTests {
    @Test void highBitsAndNegativeHashAreMappedDeterministically() {
        var router = new LikePartitionRouter(LikeTestProperties.defaults());
        assertEquals(1, router.partition(1));
        assertEquals(1, router.partition(1L << 32));
        assertEquals(0, router.partition(Long.MAX_VALUE));
        assertEquals(4, router.queues().size());
        assertEquals("article.like.set.p1", router.routingKey(router.partition(1)));
    }

    @Test void everyPartitionHasOneBindingAndSingleActiveQuorumProtection() {
        var p = LikeTestProperties.defaults();
        var topology = LikeRabbitConfiguration.topology(p);
        var queues = topology.getDeclarablesByType(Queue.class);
        var bindings = topology.getDeclarablesByType(Binding.class);
        assertEquals(5, queues.size());
        assertEquals(5, bindings.size());
        var router = new LikePartitionRouter(p);
        for (int partition = 0; partition < 4; partition++) {
            String queueName = router.queue(partition), route = router.routingKey(partition);
            var queue = queues.stream().filter(q -> q.getName().equals(queueName)).findFirst().orElseThrow();
            assertTrue(queue.isDurable());
            assertEquals("quorum", queue.getArguments().get("x-queue-type"));
            assertEquals(true, queue.getArguments().get("x-single-active-consumer"));
            assertEquals("at-least-once", queue.getArguments().get("x-dead-letter-strategy"));
            assertEquals(p.name("dead.x"), queue.getArguments().get("x-dead-letter-exchange"));
            assertEquals(1, bindings.stream().filter(b -> b.getDestination().equals(queueName) && b.getRoutingKey().equals(route)).count());
        }
    }
}
