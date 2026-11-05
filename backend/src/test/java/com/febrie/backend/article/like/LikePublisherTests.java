package com.febrie.backend.article.like;

import com.febrie.backend.article.like.infrastructure.*;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 独立验证Confirm/Return和未知结果分类，真实Broker另有集成测试。 */
class LikePublisherTests {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final ConnectionFactory factory = mock(ConnectionFactory.class);
    private final Message message = new Message(new byte[]{1}, new MessageProperties());
    private final LikePublisher publisher;

    LikePublisherTests() {
        when(rabbit.getConnectionFactory()).thenReturn(factory);
        doReturn(mock(Connection.class)).when(factory).createConnection();
        publisher = new LikePublisher(rabbit, properties(), mock(LikeCodec.class), mock(LikeTelemetry.class));
    }

    @Test void ackIsNotSuccessWhenMessageWasReturned() {
        doAnswer(call -> {
            CorrelationData data = call.getArgument(3);
            data.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "x", "key"));
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertEquals("LIKE_PUBLISH_REJECTED", assertThrows(ApiException.class, () -> publisher.publish(message)).code());
    }

    @Test void confirmationTimeoutAndSendFailureAreUnknown() {
        assertEquals("LIKE_WRITE_RESULT_UNKNOWN", assertThrows(ApiException.class, () -> publisher.publish(message)).code());
        doThrow(new IllegalStateException()).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertEquals("LIKE_WRITE_RESULT_UNKNOWN", assertThrows(ApiException.class, () -> publisher.publish(message)).code());
    }

    @Test void failureBeforePublishIsUnavailableAndConfirmedMessageSucceeds() {
        when(factory.createConnection()).thenThrow(new IllegalStateException());
        assertEquals("LIKE_PUBLISH_UNAVAILABLE", assertThrows(ApiException.class, () -> publisher.publish(message)).code());
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        doReturn(mock(Connection.class)).when(factory).createConnection();
        doAnswer(call -> {
            ((CorrelationData) call.getArgument(3)).getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        assertDoesNotThrow(() -> publisher.publish(message));
    }

    static LikeProperties properties() {
        return new LikeProperties(true, true, false, "ec:test:unit:like", Duration.ofMillis(20),
                128, 2, 20, 67108864, 60, 4, Duration.ofMillis(300), "none", -1, 0);
    }
}
