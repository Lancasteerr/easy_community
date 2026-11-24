package com.febrie.backend.article.comment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.febrie.backend.shared.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import tools.jackson.databind.json.JsonMapper;

/** 用屏障暂停首次回源，验证竞争者和过载请求的行为。 */
class CommentRefreshConcurrencyTests {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach void closeRegistry() { meters.close(); }

    @Test
    void oneColdRequestRebuildsWhileCompetitorUsesItsSharedWaitBudget() throws Exception {
        var store = mock(CommentStore.class);
        var cache = mock(CommentSnapshotCache.class);
        var key = new CommentListKey(1, 0, CommentListKey.Sort.LATEST);
        var held = new AtomicBoolean();
        var current = new AtomicReference<CommentSnapshot>();
        var rebuilding = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var snapshot = new CommentSnapshot(UUID.randomUUID().toString(), "0", 1, 2, 0);
        when(cache.current(key)).thenAnswer(call -> current.get());
        when(cache.available(key, snapshot)).thenReturn(true);
        when(cache.lock(key)).thenAnswer(call -> held.compareAndSet(false, true) ? "leader" : null);
        doAnswer(call -> {
            rebuilding.countDown();
            assertTrue(finish.await(4, TimeUnit.SECONDS));
            return List.of();
        }).when(store).indexBatch(eq(key), isNull(), anyInt());
        when(cache.publish(eq(key), isNull(), anyString(), eq("leader"), eq(0L), anyLong(), eq(false)))
                .thenAnswer(call -> {
                    current.set(snapshot);
                    return snapshot;
                });

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var snapshots = new CommentSnapshots(store, cache, Clock.systemUTC(), meters);
            var first = threads.submit(() -> snapshots.current(key));
            try {
                assertTrue(rebuilding.await(2, TimeUnit.SECONDS));
                assertEquals("COMMENT_UNAVAILABLE",
                        assertThrows(ApiException.class, () -> snapshots.current(key)).code());
            } finally {
                finish.countDown();
            }
            assertSame(snapshot, first.get(2, TimeUnit.SECONDS));
            assertSame(snapshot, snapshots.current(key));
            verify(store).indexBatch(eq(key), isNull(), anyInt());
        }
    }

    @Test
    void saturatedPublisherNeverRunsRabbitOrRedisWorkOnTheCallingThread() {
        var publisher = mock(ThreadPoolTaskExecutor.class);
        doThrow(new TaskRejectedException("测试队列饱和")).when(publisher).execute(any(Runnable.class));
        var snapshots = mock(CommentSnapshots.class);
        var rabbit = mock(RabbitTemplate.class);
        var cache = mock(CommentSnapshotCache.class);
        var refreshes = new CommentRefreshes(rabbit, publisher, cache, snapshots,
                new JsonMapper(), Clock.systemUTC(), meters, "test.comment.refresh.q");
        refreshes.offer(new CommentListKey(1, 0, CommentListKey.Sort.HOT), UUID.randomUUID().toString());
        verify(snapshots).event("publish_saturated");
        verifyNoInteractions(rabbit, cache);
    }
}
