package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.BatchReadGate;
import com.febrie.backend.article.like.application.LikeErrors;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.like.application.LikeReader;
import com.febrie.backend.article.like.infrastructure.config.LikeProperties;
import com.febrie.backend.article.like.infrastructure.observability.LikeTelemetry;
import com.febrie.backend.article.like.infrastructure.redis.LikeCache;

import com.febrie.backend.article.like.domain.LikeState;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 查询批次、用户隔离和依赖故障语义测试，不依赖缓存实现细节。 */
class LikeQueryServiceTests {
    final LikeCache cache=mock(LikeCache.class);
    final LikeReader reader=mock(LikeReader.class);
    LikeQueryService service() {
        return service(Duration.ofSeconds(10));
    }
    private LikeQueryService service(Duration readTimeout) {
        var p = new LikeProperties("ec:test:unit:like", Duration.ofSeconds(2), 128, 4, 20, 67108864,
                60, readTimeout, "none", -1, 0, 20, Duration.ofMillis(100));
        return new LikeQueryService(cache,reader,p,mock(LikeTelemetry.class));
    }
    @Test void fiftyArticlesUseOnlyTwoBatchQueriesAndVisitorsNeverQueryRelations() {
        var ids=java.util.stream.LongStream.rangeClosed(1,50).boxed().toList();
        Map<Long,Long> counts=new LinkedHashMap<>(); Map<Long,LikeState> states=new LinkedHashMap<>();
        ids.forEach(id->{counts.put(id,7L);states.put(id,new LikeState(1,id,id%2==0,10));});
        when(reader.counts(any())).thenReturn(counts);when(reader.states(eq(1L),any())).thenReturn(states);
        when(cache.putStates(eq(1L),any(),eq("C"))).thenReturn(states);
        var query=service();
        try {
            var values=query.summaries(1L,ids); assertEquals(50,values.size());assertTrue(values.get(2L).likedByMe());
            verify(reader,times(1)).counts(any());verify(reader,times(1)).states(eq(1L),any());
            clearInvocations(reader);
            assertFalse(query.summaries(null,ids).get(2L).likedByMe());verify(reader,never()).states(anyLong(),any());
        } finally {query.destroy();}
    }
    @Test void redisFailureFallsBackButMissingSqlDataFailsWholeResponse() {
        when(cache.counts(any())).thenThrow(new IllegalStateException());when(cache.states(anyLong(),any())).thenThrow(new IllegalStateException());
        when(reader.counts(any())).thenReturn(Map.of(2L,3L));when(reader.states(eq(1L),any())).thenReturn(Map.of(2L,new LikeState(1,2,false,0)));
        when(cache.putStates(anyLong(),any(),anyString())).thenThrow(new IllegalStateException());
        var query=service();
        try {
            assertEquals(3,query.summaries(1L,List.of(2L)).get(2L).likeCount());
            when(reader.counts(any())).thenThrow(LikeErrors.unavailable());
            assertEquals(503,assertThrows(ApiException.class,()->query.summaries(null,List.of(2L))).status().value());
        } finally {query.destroy();}
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fiveDifferentColdCountOrStateBatchesCanLoadConcurrently(boolean loadStates) throws Exception {
        var entered = new CountDownLatch(5);
        var release = new CountDownLatch(1);
        if (loadStates) {
            when(cache.counts(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                return Map.of(ids.iterator().next(), 3L);
            });
            when(reader.states(eq(1L), any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(1);
                long id = ids.iterator().next();
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "状态回源释放超时");
                return Map.of(id, new LikeState(1, id, true, 1));
            });
            when(cache.putStates(eq(1L), any(), eq("C"))).thenAnswer(call -> {
                Collection<LikeState> states = call.getArgument(1);
                LikeState state = states.iterator().next();
                return Map.of(state.articleId(), state);
            });
        } else {
            when(reader.counts(any())).thenAnswer(call -> {
                Collection<Long> ids = call.getArgument(0);
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "计数回源释放超时");
                return Map.of(ids.iterator().next(), 3L);
            });
        }
        var query = service();
        try (var workers = Executors.newFixedThreadPool(5)) {
            var requests = new ArrayList<Future<LikeQueryService.Summary>>();
            try {
                for (long id = 1; id <= 5; id++) {
                    long articleId = id;
                    requests.add(workers.submit(() ->
                            query.summaries(loadStates ? 1L : null, List.of(articleId)).get(articleId)));
                }
                // 所有 SQL 批次到达后才放行，验证回源执行器不会把第 5 个批次挡住。
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            for (var request : requests) {
                assertEquals(new LikeQueryService.Summary(3L, loadStates), request.get(5, TimeUnit.SECONDS));
            }
        } finally { query.destroy(); }
    }

    @Test void coalescesConcurrentSameKeys() throws Exception {
        var queue=new LinkedBlockingQueue<Runnable>();
        var gate=new BatchReadGate<Long,Long>(queue::add,Duration.ofSeconds(3));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->gate.load(List.of(1L,2L),keys->{calls.incrementAndGet();return Map.of(1L,10L,2L,20L);}));
            Runnable work=queue.poll(1,TimeUnit.SECONDS);assertNotNull(work);
            var followerThread=new AtomicReference<Thread>();
            var second=workers.submit(()->{followerThread.set(Thread.currentThread());return gate.load(List.of(1L,2L),keys->{calls.incrementAndGet();return Map.of(1L,10L,2L,20L);});});
            // 第二个请求明确等待共享Future后才放行领导者，避免依赖调度概率。
            await().atMost(Duration.ofSeconds(1)).until(()->followerThread.get()!=null&&followerThread.get().getState()==Thread.State.TIMED_WAITING);
            work.run();assertEquals(20L,first.get(1,TimeUnit.SECONDS).get(2L));
            assertEquals(10L,second.get(1,TimeUnit.SECONDS).get(1L));assertEquals(1,calls.get());assertTrue(queue.isEmpty());
        }
    }

    @Test void timedOutCallerLeavesSharedWorkAvailableToAnotherCaller() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        var gate = new BatchReadGate<Long, Long>(queue::add, Duration.ofSeconds(2));
        var ids = List.of(1L);
        assertThrows(ApiException.class, () -> gate.load(ids, keys -> Map.of(1L, 10L)));
        Runnable sharedWork = queue.poll();
        assertNotNull(sharedWork);
        var followerThread = new AtomicReference<Thread>();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var follower = workers.submit(() -> {
                followerThread.set(Thread.currentThread());
                return gate.load(ids, keys -> Map.of(1L, 99L));
            });
            // 首个请求超时后，后续同键请求仍加入原任务。
            await().atMost(Duration.ofSeconds(1)).until(() -> {
                Thread thread = followerThread.get();
                return thread != null && thread.getState() == Thread.State.TIMED_WAITING;
            });
            assertTrue(queue.isEmpty());
            sharedWork.run();
            assertEquals(Map.of(1L, 10L), follower.get(1, TimeUnit.SECONDS));
        }
    }

    @Test void failedSubmissionAndFailedLoadBothAllowRetry() {
        Executor executor = mock(Executor.class);
        doThrow(new RejectedExecutionException())
                .doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        var gate = new BatchReadGate<Long, Long>(executor, Duration.ofSeconds(2));
        var ids = List.of(1L, 2L);
        // 提交失败和 SQL 失败后，整批键都可以重新加载。
        assertThrows(ApiException.class, () -> gate.load(ids, keys -> Map.of(1L, 10L, 2L, 20L)));
        assertThrows(ApiException.class, () -> gate.load(ids, keys -> { throw LikeErrors.unavailable(); }));
        assertEquals(Map.of(1L, 10L, 2L, 20L), gate.load(ids, keys -> Map.of(1L, 10L, 2L, 20L)));
        verify(executor, times(3)).execute(any(Runnable.class));
    }

    @Test void slowCacheFillDoesNotConvertKnownSqlResultIntoReadTimeout() {
        when(reader.counts(any())).thenReturn(Map.of(2L,3L));
        doAnswer(call->{
            // 明确模拟400ms缓存延迟，超过100ms的SQL等待预算；不是随机并发时序。
            new CountDownLatch(1).await(400,TimeUnit.MILLISECONDS);return null;
        }).when(cache).putCounts(any());
        var query = service(Duration.ofMillis(100));
        try{assertEquals(3,query.summaries(null,List.of(2L)).get(2L).likeCount());}
        finally{query.destroy();}
    }
}
