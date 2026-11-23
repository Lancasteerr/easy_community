package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.*;
import com.febrie.backend.article.like.infrastructure.messaging.*;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.TransactionSystemException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用屏障控制组间顺序、执行器饱和与重投边界。 */
class LikeConsumerTests {
    final LikeCodec codec = mock(LikeCodec.class);
    final LikeSqlService sql = mock(LikeSqlService.class);
    final LikeProjection projection = mock(LikeProjection.class);
    final Channel channel = mock(Channel.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final LikeArticleExecutor executor = new LikeArticleExecutor(new LikeTelemetry(meters));
    final LikeConsumer consumer = consumer(ignored -> { });

    @BeforeEach void openChannel() {
        when(channel.isOpen()).thenReturn(true);
    }

    @AfterEach void closeExecutor() {
        executor.close();
        meters.close();
    }

    @Test void fastArticleIsAcknowledgedWhileAnotherArticleRetries() throws Exception {
        var a = message(1, 2, 1, true, 10);
        var b = message(2, 6, 2, true, 10);
        var backingOff = new CountDownLatch(1);
        var resumeRetry = new CountDownLatch(1);
        var fastAck = new CountDownLatch(1);
        var delays = new ArrayList<Long>();
        var retrying = consumer(delay -> {
            delays.add(delay);
            backingOff.countDown();
            await(resumeRetry);
        });
        when(sql.applyArticle(eq(2L), anyList())).thenThrow(new TransientDataAccessResourceException("test"));
        when(sql.applyArticle(eq(6L), anyList())).thenAnswer(call -> {
            await(backingOff);
            return result(6, 2, LikeSqlService.Status.APPLIED);
        });
        doAnswer(call -> { fastAck.countDown(); return null; }).when(channel).basicAck(2, false);

        var batch = startBatch(() -> retrying.consumeBatch(2, List.of(a, b), channel));
        try {
            await(fastAck);
            verify(channel, never()).basicNack(1, false, false);
        } finally {
            resumeRetry.countDown();
        }
        batch.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L), delays);
        verify(sql, times(5)).applyArticle(eq(2L), anyList());
        verify(channel).basicNack(1, false, false);
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

        verify(sql).applyArticle(eq(2L), argThat(commands -> commands.size() == 1 && commands.getFirst().number() == 11));
        verify(channel).basicNack(12, false, false);
        var orderA = inOrder(projection, channel);
        orderA.verify(projection).committedArticle(argThat(result -> result.articleId() == 2));
        orderA.verify(channel).basicAck(11, false);
        orderA.verify(channel).basicAck(13, false);
        var orderC = inOrder(projection, channel);
        orderC.verify(projection).committedArticle(argThat(result -> result.articleId() == 10));
        orderC.verify(channel).basicAck(14, false);
        verify(channel, never()).basicAck(anyLong(), eq(true));
    }

    @Test void articleAndCommentWithTheSameIdCommitAsSeparateTargets() throws Exception {
        var article = message(20, 2, 1, true, 10);
        var comment = message(21, 2, 1, false, 11);
        var target = new LikeTarget(LikeTarget.Type.COMMENT, 2);
        var command = new LikeCommand(1, java.util.UUID.randomUUID().toString(), 1L, target, false, "11", Instant.now());
        when(codec.decode(comment.getBody())).thenReturn(command);
        when(sql.applyArticle(eq(2L), anyList())).thenReturn(result(2, 1, LikeSqlService.Status.APPLIED));
        var applied = new LikeSqlService.Result(LikeSqlService.Status.APPLIED, new LikeState(1, 2, false, 11));
        when(sql.applyTarget(target, List.of(command))).thenReturn(new LikeSqlService.ArticleResult(target, List.of(applied), 0));

        consumer.consumeBatch(2, List.of(article, comment), channel);

        verify(sql).applyArticle(eq(2L), argThat(commands -> commands.size() == 1 &&
                commands.getFirst().target().type() == LikeTarget.Type.ARTICLE));
        verify(sql).applyTarget(target, List.of(command));
        verify(channel).basicAck(20, false);
        verify(channel).basicAck(21, false);
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

    @Test void partialAckFailureCancelsOtherArticlesAndLeavesTheirDeliveryUnconfirmed() throws Exception {
        Message a1 = message(1, 2, 1, true, 10), a2 = message(2, 2, 1, true, 11), b = message(3, 6, 2, true, 10);
        var slowStarted = new CountDownLatch(1);
        var slowStopped = new CountDownLatch(1);
        var slowRelease = new CountDownLatch(1);
        var retrying = consumer(ignored -> {
            slowStarted.countDown();
            try {
                await(slowRelease);
            } catch (InterruptedException interrupted) {
                slowStopped.countDown();
                throw interrupted;
            }
        });
        when(sql.applyArticle(eq(6L), anyList())).thenThrow(new TransientDataAccessResourceException("test"));
        when(sql.applyArticle(eq(2L), anyList())).thenAnswer(call -> {
            await(slowStarted);
            return result(2, 1, LikeSqlService.Status.APPLIED);
        });
        doThrow(new IOException("断连")).when(channel).basicAck(2, false);
        try {
            retrying.consumeBatch(2, List.of(a1, a2, b), channel);
            await(slowStopped);
        } finally {
            slowRelease.countDown();
        }
        var order = inOrder(channel);
        order.verify(channel).basicAck(1, false);
        order.verify(channel).basicAck(2, false);
        order.verify(channel).abort();
        verify(channel, never()).basicAck(3, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void listenerInterruptionRestoresFlagAndCancelsWorker() throws Exception {
        var a = message(1, 2, 1, true, 10);
        var retryStarted = new CountDownLatch(1);
        var retryInterrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(sql.applyArticle(eq(2L), anyList())).thenThrow(new TransientDataAccessResourceException("test"));
        var retrying = consumer(ignored -> {
            retryStarted.countDown();
            try {
                await(release);
            } catch (InterruptedException interrupted) {
                retryInterrupted.countDown();
                throw interrupted;
            }
        });

        var batch = new FutureTask<>(() -> {
            retrying.consumeBatch(2, List.of(a), channel);
            return Thread.currentThread().isInterrupted();
        });
        var listener = Thread.ofVirtual().start(batch);
        try {
            await(retryStarted);
            listener.interrupt();
            assertTrue(batch.get(5, TimeUnit.SECONDS));
            await(retryInterrupted);
        } finally {
            release.countDown();
        }
        verify(channel).abort();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test void saturatedPoolKeepsBoundedQueueAndAcknowledgesOnListenerThread() throws Exception {
        var eightStarted = new CountDownLatch(8);
        var releaseFirst = new CountDownLatch(1);
        var releaseRest = new CountDownLatch(1);
        var firstAck = new CountDownLatch(1);
        var messages = messagesAcrossArticles(13);
        when(sql.applyArticle(anyLong(), anyList())).thenAnswer(call -> {
            long article = call.getArgument(0);
            eightStarted.countDown();
            await(article == 2 ? releaseFirst : releaseRest);
            return result(article, 1, LikeSqlService.Status.APPLIED);
        });
        var ackThreads = new ConcurrentLinkedQueue<Thread>();
        doAnswer(call -> {
            ackThreads.add(Thread.currentThread());
            if (call.<Long>getArgument(0) == 1L) {
                firstAck.countDown();
            }
            return null;
        }).when(channel).basicAck(anyLong(), eq(false));

        var batch = new FutureTask<>(() -> consumer.consumeBatch(2, messages, channel), null);
        var listener = Thread.ofVirtual().start(batch);
        try {
            await(eightStarted);
            assertEquals(8, meters.get("article.like.executor.active").gauge().value());
            assertEquals(4, meters.get("article.like.executor.queued").gauge().value());
            // 其他工作线程仍然阻塞时，已完成消息必须能越过队列饱和及时确认。
            releaseFirst.countDown();
            await(firstAck);
        } finally {
            releaseFirst.countDown();
            releaseRest.countDown();
        }
        batch.get(5, TimeUnit.SECONDS);
        verify(sql, times(13)).applyArticle(anyLong(), anyList());
        verify(channel, times(13)).basicAck(anyLong(), eq(false));
        assertTrue(ackThreads.stream().allMatch(thread -> thread == listener));
    }

    @Test void channelShutdownRemovesQueuedTasksWithoutStartingThem() throws Exception {
        var eightStarted = new CountDownLatch(8);
        var eightStopped = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var messages = messagesAcrossArticles(13);
        when(sql.applyArticle(anyLong(), anyList())).thenAnswer(call -> {
            eightStarted.countDown();
            try {
                await(release);
                return result(call.getArgument(0), 1, LikeSqlService.Status.APPLIED);
            } catch (InterruptedException interrupted) {
                eightStopped.countDown();
                throw interrupted;
            }
        });

        var batch = startBatch(() -> consumer.consumeBatch(2, messages, channel));
        try {
            await(eightStarted);
            assertEquals(4, meters.get("article.like.executor.queued").gauge().value());
            var shutdown = ArgumentCaptor.forClass(ShutdownListener.class);
            verify(channel).addShutdownListener(shutdown.capture());
            when(channel.isOpen()).thenReturn(false);
            // 模拟Broker主动断开，监听器仍在等待任务，未发出任何ACK。
            shutdown.getValue().shutdownCompleted(new ShutdownSignalException(false, false, null, channel));
            batch.get(5, TimeUnit.SECONDS);
            await(eightStopped);
            assertEquals(0, meters.get("article.like.executor.queued").gauge().value());
            verify(sql, times(8)).applyArticle(anyLong(), anyList());
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        } finally {
            release.countDown();
        }
    }

    private List<Message> messagesAcrossArticles(int count) {
        List<Message> messages = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            messages.add(message(index + 1, 2 + 4L * index, 1, true, 10));
        }
        return messages;
    }

    private LikeConsumer consumer(LikeConsumer.Delay delay) {
        return new LikeConsumer(codec, sql, projection, mock(LikeTelemetry.class),
                LikeTestProperties.defaults(), executor, delay);
    }

    private static FutureTask<Void> startBatch(Runnable batch) {
        var task = new FutureTask<Void>(batch, null);
        Thread.ofVirtual().start(task);
        return task;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "等待消费屏障超时");
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
