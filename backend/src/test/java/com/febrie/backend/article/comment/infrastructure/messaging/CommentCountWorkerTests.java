package com.febrie.backend.article.comment.infrastructure.messaging;

import com.febrie.backend.article.comment.application.command.CommentCountProjection;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCountOutbox;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCountOutbox.Event;
import com.febrie.backend.article.comment.infrastructure.redis.CommentMaintenance;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommentCountWorkerTests {
    private final CommentCountOutbox outbox = mock(CommentCountOutbox.class);
    private final CommentBroker broker = mock(CommentBroker.class);
    private final CommentCountProjection projection = mock(CommentCountProjection.class);
    private final CommentMaintenance maintenance = mock(CommentMaintenance.class);
    private final CommentCountWorker worker = new CommentCountWorker(outbox, broker, projection, maintenance);
    private final Channel channel = mock(Channel.class);

    @Test
    void repeatedAndUnorderedEventsRecountEachArticleOnceBeforeAcknowledgement() throws Exception {
        var recent = delivery(1, new Event(8, 4));
        var older = delivery(2, new Event(1, 4));
        var other = delivery(3, new Event(9, 8));
        var wrongPartition = delivery(4, new Event(10, 5));

        worker.consume(0, List.of(recent, older, other, wrongPartition), channel);

        var order = inOrder(projection, maintenance, channel);
        order.verify(channel).basicNack(4, false, false);
        order.verify(projection).recount(Set.of(4L, 8L));
        order.verify(maintenance).count(4L);
        order.verify(maintenance).count(8L);
        order.verify(channel).basicAck(1, false);
        order.verify(channel).basicAck(2, false);
        order.verify(channel).basicAck(3, false);
        order.verifyNoMoreInteractions();
    }

    @Test
    void databaseFailureRequeuesWithoutInvalidatingTheCache() throws Exception {
        doThrow(new CannotGetJdbcConnectionException("测试数据库断开"))
                .when(projection).recount(Set.of(4L));

        worker.consume(0, List.of(delivery(1, new Event(8, 4))), channel);

        verify(channel).basicNack(1, false, true);
        verifyNoMoreInteractions(channel, maintenance);
    }

    @Test
    void relayUsesStablePartitionAndRetainsUnconfirmedEvents() throws Exception {
        var router = new CommentBroker(null, null, "test");
        assertEquals(1, router.partition(5));
        assertEquals(1, router.partition(9));
        var sent = new Event(1, 5);
        var timeout = new Event(2, 9);
        var claim = new CommentCountOutbox.Claim("lease", List.of(sent, timeout));
        var first = new CorrelationData();
        var second = new CorrelationData();
        when(outbox.claim(128)).thenReturn(claim);
        when(broker.partition(anyLong())).thenAnswer(call -> router.partition(call.getArgument(0)));
        when(broker.countQueue(1)).thenReturn(router.countQueue(1));
        when(broker.send("test.comment.count.1.q", "1", sent)).thenReturn(first);
        when(broker.send("test.comment.count.1.q", "2", timeout)).thenReturn(second);
        when(broker.confirmed(eq(first), anyLong())).thenReturn(true);

        worker.relay();

        // 未得到确认的事件由 SQL 继续保留，可在下一轮重投。
        verify(outbox).finish(claim, Set.of(1L));
    }

    private Message delivery(long tag, Event event) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        var message = new Message(new byte[0], properties);
        when(broker.decode(same(message), eq(Event.class))).thenReturn(event);
        when(broker.partition(event.articleId())).thenReturn((int) (event.articleId() % 4));
        return message;
    }
}
