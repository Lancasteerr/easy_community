package com.febrie.backend.article.comment.infrastructure.search;

import com.febrie.backend.article.comment.infrastructure.messaging.CommentBroker;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks.Document;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentIndexTasks.Event;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CommentIndexWorkerTests {
    private final CommentIndexTasks tasks = mock(CommentIndexTasks.class);
    private final CommentIndex index = mock(CommentIndex.class);
    private final CommentBroker broker = mock(CommentBroker.class);
    private final CommentIndexWorker worker = new CommentIndexWorker(tasks, index, broker, 200);

    @Test
    void mergesWritesAndAcknowledgesEachDeliveryByItsOwnVersion() throws Exception {
        var newer = delivery(1, new Event(10, 5, 4));
        var older = delivery(2, new Event(11, 5, 2));
        var failed = delivery(3, new Event(12, 6, 3));
        var invalid = delivery(4, new Event(0, 9, 1));
        var eligible = new Document(5, 1, 10, false, 3, 0);
        var rejected = new Document(6, 1, 11, true, 3, 0);
        var channel = mock(Channel.class);
        when(tasks.load(Set.of(5L, 6L))).thenReturn(List.of(eligible, rejected));
        when(index.write(List.of(eligible, rejected))).thenReturn(Set.of(5L));

        worker.consume(List.of(newer, older, failed, invalid), channel);

        // 同一评论的新旧消息共享一次索引，结果仍各自确认。
        verify(tasks).load(Set.of(5L, 6L));
        verify(index).write(List.of(eligible, rejected));
        verifyNoMoreInteractions(tasks, index);
        verify(channel).basicNack(1, false, true);
        verify(channel).basicAck(2, false);
        verify(channel).basicNack(3, false, true);
        verify(channel).basicNack(4, false, false);
        verifyNoMoreInteractions(channel);
    }

    @Test
    void clearsOnlyConfirmedEventsAfterTheRelayWindow() throws Exception {
        var sent = new Event(1, 5, 1);
        var unconfirmed = new Event(2, 6, 1);
        var failed = new Event(3, 7, 1);
        var claim = new CommentIndexTasks.Claim("lease", List.of(sent, unconfirmed, failed));
        var success = new CorrelationData();
        var timeout = new CorrelationData();
        when(tasks.claimLifecycle(200)).thenReturn(claim);
        when(broker.indexQueue()).thenReturn("index");
        when(broker.send("index", "1", sent)).thenReturn(success);
        when(broker.send("index", "2", unconfirmed)).thenReturn(timeout);
        when(broker.send("index", "3", failed)).thenThrow(new IllegalStateException("投递失败"));
        when(broker.confirmed(eq(success), anyLong())).thenReturn(true);

        worker.relay();

        verify(tasks).finishLifecycle(claim, Set.of(1L));
    }

    private Message delivery(long tag, Event event) {
        var properties = new MessageProperties();
        properties.setDeliveryTag(tag);
        var message = new Message(new byte[0], properties);
        when(broker.decode(same(message), eq(Event.class))).thenReturn(event);
        return message;
    }
}
