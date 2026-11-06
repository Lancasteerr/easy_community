package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.LikeProjection;
import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.infrastructure.messaging.LikeCodec;
import com.febrie.backend.article.like.infrastructure.messaging.LikeConsumer;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;

import com.febrie.backend.article.like.domain.*;
import com.rabbitmq.client.Channel;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.dao.TransientDataAccessResourceException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 用可控等待器验证有界退避、隔离和ACK时序，不真实sleep。 */
class LikeConsumerTests {
    @Test void boundedRetriesKeepMessageUntilDeadLetterTransfer() throws Exception {
        var codec = mock(LikeCodec.class); var sql = mock(LikeSqlService.class); var channel = mock(Channel.class);
        when(codec.decode(any())).thenReturn(LikeSqlServiceTests.command(true, 10));
        when(sql.apply(any())).thenThrow(new TransientDataAccessResourceException("test"));
        var delays = new ArrayList<Long>();
        new LikeConsumer(codec, sql, projection(), mock(LikeTelemetry.class), delays::add).consume(message(), channel);
        assertEquals(java.util.List.of(1000L, 2000L, 4000L, 8000L), delays);
        verify(sql, times(5)).apply(any());
        verify(channel).basicNack(7, false, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test void staleDoesNotWriteOriginalMessageAndCacheFailureDoesNotUndoAck() throws Exception {
        var codec = mock(LikeCodec.class); var sql = mock(LikeSqlService.class); var channel = mock(Channel.class);
        var projection = mock(LikeProjection.class);
        when(codec.decode(any())).thenReturn(LikeSqlServiceTests.command(true, 10));
        when(sql.apply(any())).thenReturn(new LikeSqlService.Result(LikeSqlService.Status.STALE, new LikeState(1, 2, false, 12)));
        var consumer = new LikeConsumer(codec, sql, projection, mock(LikeTelemetry.class), ignored -> { });
        consumer.consume(message(), channel);
        verifyNoInteractions(projection);
        when(sql.apply(any())).thenReturn(new LikeSqlService.Result(LikeSqlService.Status.APPLIED, new LikeState(1, 2, true, 10)));
        doThrow(new IllegalStateException()).when(projection).committed(any());
        consumer.consume(message(), channel);
        verify(channel, times(2)).basicAck(7, false);
    }

    @Test void unknownCommitMustBeResolvedByAnotherSqlAttemptBeforeAck() throws Exception {
        var codec=mock(LikeCodec.class);var sql=mock(LikeSqlService.class);var channel=mock(Channel.class);
        when(codec.decode(any())).thenReturn(LikeSqlServiceTests.command(true,10));
        when(sql.apply(any())).thenThrow(new org.springframework.transaction.TransactionSystemException("提交结果未知"))
                .thenReturn(new LikeSqlService.Result(LikeSqlService.Status.DUPLICATE,new LikeState(1,2,true,10)));
        new LikeConsumer(codec,sql,projection(),mock(LikeTelemetry.class),ignored->{ }).consume(message(),channel);
        var order=inOrder(sql,channel);
        order.verify(sql,times(2)).apply(any());order.verify(channel).basicAck(7,false);
        verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }

    private static LikeProjection projection() { return mock(LikeProjection.class); }
    private static Message message() { var p = new MessageProperties(); p.setDeliveryTag(7); return new Message(new byte[]{1}, p); }
}
