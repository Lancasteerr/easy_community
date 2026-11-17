package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.messaging.*;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 通过可控SQL和确认故障验证组间独立，不启动Web或真实等待退避。 */
class LikeConsumerTests {
    final LikeCodec codec = mock(LikeCodec.class);
    final LikeSqlService sql = mock(LikeSqlService.class);
    final LikeProjection projection = mock(LikeProjection.class);
    final Channel channel = mock(Channel.class);
    final List<Long> delays = new ArrayList<>();
    final LikeConsumer consumer = new LikeConsumer(codec, sql, projection, mock(LikeTelemetry.class),
            LikeTestProperties.defaults(), delays::add);

    @Test void oneArticleRetryExhaustionDoesNotBlockFollowingArticle() throws Exception {
        var a = message(1, 2, 1, true, 10);
        var b = message(2, 6, 2, true, 10);
        when(sql.applyArticle(eq(2L), anyList())).thenThrow(new TransientDataAccessResourceException("test"));
        when(sql.applyArticle(eq(6L), anyList())).thenReturn(result(6, 2, LikeSqlService.Status.APPLIED));
        consumer.consumeBatch(2, List.of(a, b), channel);
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L), delays);
        verify(sql, times(5)).applyArticle(eq(2L), anyList());
        verify(channel).basicNack(1, false, false);
        verify(channel).basicAck(2, false);
    }

    @Test void groupsCommitAndAcknowledgeInterleavedOriginalTagsIndependently() throws Exception {
        var a1 = message(11, 2, 1, true, 10);
        var b = message(12, 6, 2, true, 10);
        var a2 = message(13, 2, 1, false, 11);
        var c = message(14, 10, 3, true, 10);
        when(sql.applyArticle(eq(2L), anyList())).thenReturn(result(2, 1, LikeSqlService.Status.APPLIED));
        when(sql.applyArticle(eq(6L), anyList())).thenThrow(new LikeInvariantException("test"));
        when(sql.applyArticle(eq(10L), anyList())).thenReturn(result(10, 3, LikeSqlService.Status.APPLIED));
        consumer.consumeBatch(2, List.of(a1, b, a2, c), channel);
        var order = inOrder(sql, projection, channel);
        order.verify(sql).applyArticle(eq(2L), argThat(commands -> commands.size() == 1 && commands.getFirst().number() == 11));
        order.verify(projection).committedArticle(any());
        order.verify(channel).basicAck(11, false); order.verify(channel).basicAck(13, false);
        order.verify(sql).applyArticle(eq(6L), anyList()); order.verify(channel).basicNack(12, false, false);
        order.verify(sql).applyArticle(eq(10L), anyList()); order.verify(projection).committedArticle(any());
        order.verify(channel).basicAck(14, false);
        verify(channel, never()).basicAck(anyLong(), eq(true));
    }

    @Test void invalidWrongPartitionAndConflictingRelationAreIsolatedOnly() throws Exception {
        var bad = new Message(new byte[]{99}, new MessageProperties()); bad.getMessageProperties().setDeliveryTag(9);
        when(codec.decode(bad.getBody())).thenThrow(new IllegalArgumentException("bad"));
        var wrong = message(10, 3, 4, true, 10);
        var conflict1 = message(11, 2, 1, true, 10);
        var conflict2 = message(12, 2, 1, false, 10);
        var shadow = message(13, 2, 1, true, 20);
        var good = message(14, 2, 2, true, 20);
        when(sql.applyArticle(eq(2L), anyList())).thenReturn(result(2, 2, LikeSqlService.Status.APPLIED));
        consumer.consumeBatch(2, List.of(bad, wrong, conflict1, conflict2, shadow, good), channel);
        for (long tag : List.of(9L, 10L, 11L, 12L, 13L)) { verify(channel).basicNack(tag, false, false); }
        verify(sql).applyArticle(eq(2L), argThat(commands -> commands.size() == 1 && commands.getFirst().userId() == 2));
        verify(channel).basicAck(14, false);
    }

    @Test void unknownCommitIsResolvedBeforeAckAndCacheFailureDoesNotUndoCommit() throws Exception {
        var message = message(7, 2, 1, true, 10);
        when(sql.applyArticle(eq(2L), anyList())).thenThrow(new TransactionSystemException("未知"))
                .thenReturn(result(2, 1, LikeSqlService.Status.DUPLICATE));
        doThrow(new IllegalStateException()).when(projection).committedArticle(any());
        consumer.consumeBatch(2, List.of(message), channel);
        var order = inOrder(sql, channel);
        order.verify(sql, times(2)).applyArticle(eq(2L), anyList()); order.verify(channel).basicAck(7, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void partialAckFailureClosesChannelWithoutProcessingRemainingArticle() throws Exception {
        Message a1 = message(1, 2, 1, true, 10), a2 = message(2, 2, 1, true, 11), b = message(3, 6, 2, true, 10);
        when(sql.applyArticle(eq(2L), anyList())).thenReturn(result(2, 1, LikeSqlService.Status.APPLIED));
        doThrow(new IOException("断连")).when(channel).basicAck(2, false);
        consumer.consumeBatch(2, List.of(a1, a2, b), channel);
        verify(channel).basicAck(1, false); verify(channel).abort();
        verify(sql, never()).applyArticle(eq(6L), anyList());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void interruptionRetainsUnconfirmedMessagesAndRestoresFlag() throws Exception {
        var a = message(1, 2, 1, true, 10);
        when(sql.applyArticle(eq(2L), anyList())).thenThrow(new TransientDataAccessResourceException("test"));
        var interrupted = new LikeConsumer(codec, sql, projection, mock(LikeTelemetry.class),
                LikeTestProperties.defaults(), ignored -> { throw new InterruptedException(); });
        try {
            interrupted.consumeBatch(2, List.of(a), channel);
            assertTrue(Thread.currentThread().isInterrupted());
            verify(channel).abort();
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        } finally { Thread.interrupted(); }
    }

    private Message message(long tag, long article, long user, boolean liked, long version) {
        var command = new LikeCommand(1, UUID.randomUUID().toString(), user, article, liked, Long.toString(version), Instant.now());
        var properties = new MessageProperties(); properties.setDeliveryTag(tag);
        byte[] body = Long.toString(tag).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(codec.decode(body)).thenReturn(command);
        return new Message(body, properties);
    }
    private static LikeSqlService.ArticleResult result(long article, long user, LikeSqlService.Status status) {
        return new LikeSqlService.ArticleResult(article,
                List.of(new LikeSqlService.Result(status, new LikeState(user, article, true, 10))), 1);
    }
}
