package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.*;
import com.febrie.backend.article.like.domain.LikeState;
import com.febrie.backend.article.like.infrastructure.*;
import com.febrie.backend.shared.error.ApiException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 查询批次、用户隔离和依赖故障语义测试，不依赖缓存实现细节。 */
class LikeQueryServiceTests {
    final LikeCache cache=mock(LikeCache.class);
    final LikeReader reader=mock(LikeReader.class);
    LikeQueryService service() {
        var p=new LikeProperties(true,false,false,"ec:test:unit:like",Duration.ofSeconds(2),128,2,20,67108864,60,4,Duration.ofSeconds(2),"none",-1,0);
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
    @Test void readGateRejectsOverloadAndCoalescesConcurrentSameKeys() throws Exception {
        var queue=new LinkedBlockingQueue<Runnable>();
        var gate=new BatchReadGate<Long,Long>(queue::add,new Semaphore(1),Duration.ofSeconds(3));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->gate.load(List.of(1L,2L),keys->{calls.incrementAndGet();return Map.of(1L,10L,2L,20L);}));
            Runnable work=queue.poll(1,TimeUnit.SECONDS);assertNotNull(work);
            var second=workers.submit(()->gate.load(List.of(1L,2L),keys->{calls.incrementAndGet();return Map.of(1L,10L,2L,20L);}));
            // 即使第二个请求晚于完成到达，过载保护也不会制造额外未受控SQL。
            work.run();assertEquals(20L,first.get(1,TimeUnit.SECONDS).get(2L));
            while(!second.isDone()) {var extra=queue.poll(50,TimeUnit.MILLISECONDS);if(extra!=null){extra.run();}}
            assertEquals(10L,second.get().get(1L));assertTrue(calls.get()<=2);
        }
        var overloaded=new BatchReadGate<Long,Long>(Runnable::run,new Semaphore(0),Duration.ofMillis(10));
        assertThrows(ApiException.class,()->overloaded.load(List.of(1L),keys->{throw new AssertionError("不能越过并发上限");}));
    }
}
