package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.messaging.*;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.rabbitmq.client.Channel;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/** 仅连接专用测试vhost；同JVM验证原生批量监听与接管，不启动Web、Redis或子进程。 */
@EnabledIfEnvironmentVariable(named = "IT_LIKE", matches = "true")
class LikePartitionMessagingIntegrationTests {
    final List<CachingConnectionFactory> connections = new ArrayList<>();
    final List<LikeListenerGroup> groups = new ArrayList<>();
    LikeProperties properties;
    LikePartitionRouter router;
    RabbitAdmin admin;
    LikePublisher publisher;
    final LikeCodec codec = new LikeCodec(new ObjectMapper());

    @BeforeEach void declare() {
        String vhost = System.getenv("IT_RABBITMQ_VHOST");
        assertNotNull(vhost); assertTrue(vhost.contains("test"), "只允许专用测试vhost");
        properties = new LikeProperties("ec:test:partition:" + UUID.randomUUID() + ":like", Duration.ofSeconds(2),
                128, 4, 20, 67108864, 60, 4, Duration.ofMillis(300), "none", -1, 0, 20, Duration.ofMillis(100));
        router = new LikePartitionRouter(properties);
        var connection = connection();
        admin = new RabbitAdmin(connection);
        for (var declaration : LikeRabbitConfiguration.topology(properties).getDeclarables()) {
            if (declaration instanceof Exchange exchange) { admin.declareExchange(exchange); }
            else if (declaration instanceof Queue queue) { admin.declareQueue(queue); }
            else if (declaration instanceof Binding binding) { admin.declareBinding(binding); }
        }
        var template = new RabbitTemplate(connection); template.setMandatory(true);
        publisher = new LikePublisher(template, properties, codec, mock(LikeTelemetry.class));
    }

    @AfterEach void cleanup() {
        groups.forEach(LikeListenerGroup::stop); groups.forEach(LikeListenerGroup::destroy);
        if (admin != null) {
            router.queues().forEach(admin::deleteQueue); admin.deleteQueue(properties.name("dead.q"));
            admin.deleteExchange(properties.name("command.x")); admin.deleteExchange(properties.name("dead.x"));
        }
        connections.forEach(CachingConnectionFactory::destroy);
    }

    @Test void nativeBatchCoalescesAndIdlePartialBatchIsDeliveredPromptly() {
        var sql = mock(LikeSqlService.class);
        when(sql.applyArticle(anyLong(), anyList())).thenAnswer(call -> {
            long article = call.getArgument(0);
            List<LikeCommand> commands = call.getArgument(1);
            return new LikeSqlService.ArticleResult(article, commands.stream().map(c ->
                    new LikeSqlService.Result(LikeSqlService.Status.APPLIED,
                            new LikeState(c.userId(), article, c.desiredLiked(), c.number()))).toList(), 0);
        });
        var projection = mock(LikeProjection.class);
        var consumer = new LikeConsumer(codec, sql, projection, mock(LikeTelemetry.class), properties);
        // 先堆积同分区的两篇文章，使原生批次必然包含重复关系和混合文章。
        publisher.publish(command(1, 1, true, 10)); publisher.publish(command(1, 1, false, 11));
        publisher.publish(command(2, 5, true, 10));
        var group = group(connection(), consumer); group.start();
        verify(sql, timeout(5000)).applyArticle(eq(1L), argThat(c -> c.size() == 1 && c.getFirst().number() == 11));
        verify(sql, timeout(5000)).applyArticle(eq(5L), anyList());
        verify(projection, timeout(5000).times(2)).committedArticle(any());
        // 单条新消息不需要等到20条才提交。
        publisher.publish(command(3, 9, true, 10));
        verify(sql, timeout(2000)).applyArticle(eq(9L), anyList());
        group.stop();
        for (String queue : router.queues()) { assertEquals(0, admin.getQueueInfo(queue).getMessageCount()); }
    }

    @Test void standbyTakesOverUnacknowledgedMessageAfterActiveConnectionCloses() throws Exception {
        var first = mock(LikeConsumer.class); var second = mock(LikeConsumer.class);
        var firstReceived = new CountDownLatch(1); var takenOver = new CountDownLatch(1);
        var redelivered = new AtomicBoolean();
        doAnswer(call -> { firstReceived.countDown(); return null; }).when(first).consumeBatch(anyInt(), anyList(), any(Channel.class));
        doAnswer(call -> {
            List<Message> messages = call.getArgument(1); Channel channel = call.getArgument(2);
            for (var message : messages) {
                redelivered.set(Boolean.TRUE.equals(message.getMessageProperties().getRedelivered()));
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
            }
            takenOver.countDown(); return null;
        }).when(second).consumeBatch(anyInt(), anyList(), any(Channel.class));
        var activeConnection = connection(); var active = group(activeConnection, first); active.start();
        String queue = router.queue(router.partition(1));
        await().atMost(Duration.ofSeconds(5)).until(() -> admin.getQueueInfo(queue).getConsumerCount() == 1);
        var standby = group(connection(), second); standby.start();
        // Quorum单活队列的AMQP计数只报告活跃消费者；接管由下面的真实投递验证。
        assertTrue(standby.isRunning());
        assertEquals(1, admin.getQueueInfo(queue).getConsumerCount());
        publisher.publish(command(1, 1, true, 10));
        assertTrue(firstReceived.await(5, TimeUnit.SECONDS)); verifyNoInteractions(second);
        // 真实关闭活跃连接，不ACK；备用消费同一原始命令，数据库幂等另由MySQL测试验证。
        activeConnection.resetConnection(); active.stop();
        assertTrue(takenOver.await(5, TimeUnit.SECONDS)); assertTrue(redelivered.get());
        standby.stop(); assertEquals(0, admin.getQueueInfo(queue).getMessageCount());
    }

    private LikeListenerGroup group(CachingConnectionFactory factory, LikeConsumer consumer) {
        var group = new LikeListenerGroup(factory, properties, router, consumer);
        group.afterPropertiesSet(); groups.add(group); return group;
    }
    private CachingConnectionFactory connection() {
        var connection = new CachingConnectionFactory(System.getenv().getOrDefault("IT_RABBITMQ_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("IT_RABBITMQ_PORT", "5672")));
        connection.setUsername(System.getenv("IT_RABBITMQ_USERNAME")); connection.setPassword(System.getenv("IT_RABBITMQ_PASSWORD"));
        connection.setVirtualHost(System.getenv("IT_RABBITMQ_VHOST"));
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); connection.setPublisherReturns(true);
        connections.add(connection); return connection;
    }
    private static LikeCommand command(long user, long article, boolean liked, long version) {
        return new LikeCommand(1, UUID.randomUUID().toString(), user, article, liked, Long.toString(version), Instant.now());
    }
}
