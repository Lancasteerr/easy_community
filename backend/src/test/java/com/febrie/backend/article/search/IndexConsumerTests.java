package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleIndexStore;
import com.febrie.backend.article.search.infrastructure.messaging.IndexConsumer;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;

/** 重复事件不读SQL，合法新事件跳到主库最新版本后才ACK。 */
class IndexConsumerTests {
    @Test void skipsDuplicateAndAcknowledgesLatestSnapshot() throws Exception {
        var store = mock(ArticleIndexStore.class); var reader = mock(ArticleIndexReader.class);
        var channel = mock(Channel.class); var events = mock(ApplicationEventPublisher.class);
        var json = new ObjectMapper();
        var consumer = new IndexConsumer(reader, store, json, events, new SimpleMeterRegistry());
        var properties = new MessageProperties(); properties.setDeliveryTag(7);
        var message = new Message(json.writeValueAsBytes(new IndexEvent(1, 2, 9)), properties);
        when(store.metaVersion(2)).thenReturn(11L);
        consumer.consume(message, channel);
        verifyNoInteractions(reader); verify(channel).basicAck(7, false);
        reset(channel); clearInvocations(store); when(store.metaVersion(2)).thenReturn(8L);
        var snapshot = new ArticleIndexMetadata(2, 9L, "标题", Instant.now(), true, 11, List.of());
        when(reader.load(2)).thenReturn(snapshot);
        consumer.consume(message, channel);
        var order = inOrder(store, channel);
        order.verify(store).metaVersion(2); order.verify(store).upsert(snapshot); order.verify(channel).basicAck(7, false);
    }
    @Test void missingSqlRowIsDeadLetteredInsteadOfAcknowledged() throws Exception {
        var channel = mock(Channel.class); var json = new ObjectMapper();
        var consumer = new IndexConsumer(mock(ArticleIndexReader.class), mock(ArticleIndexStore.class), json,
                mock(ApplicationEventPublisher.class), new SimpleMeterRegistry());
        consumer.consume(new Message(json.writeValueAsBytes(new IndexEvent(1, 2, 1))), channel);
        verify(channel).basicNack(0, false, false); verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }
}
