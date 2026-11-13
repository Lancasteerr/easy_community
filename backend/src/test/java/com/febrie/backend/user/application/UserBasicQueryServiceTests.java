package com.febrie.backend.user.application;

import com.febrie.backend.shared.cache.CacheValue;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserBasicData;
import com.febrie.backend.user.infrastructure.cache.UserBasicCacheStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证批量合并、逐项释放和失效回填拒绝；token原子性另由真Redis覆盖。 */
class UserBasicQueryServiceTests {
    private final UserBasicReader reader = mock(UserBasicReader.class);
    private final UserBasicCacheStore cache = mock(UserBasicCacheStore.class);
    private final UserBasicQueryService service = new UserBasicQueryService(reader, cache);

    @Test void warmAndNegativeHitsNeverCompeteForLocks() {
        when(cache.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.hit(user(1)), 2L, CacheValue.negative()));
        assertEquals(Map.of(1L, user(1)), service.get(List.of(1L, 2L, 1L)));
        verifyNoInteractions(reader);
        verify(cache, never()).tryLock(anyLong());
    }

    @Test void mergesMissingIdsAndReleasesEachBeforeWritingNext() {
        when(cache.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss(), 2L, CacheValue.miss()));
        when(cache.tryLock(1)).thenReturn("a"); when(cache.tryLock(2)).thenReturn("b");
        when(reader.load(List.of(1L, 2L))).thenReturn(Map.of(1L, user(1)));
        when(cache.publish(1, "a", user(1))).thenReturn(true);
        when(cache.missing(2, "b")).thenReturn(true);
        assertEquals(Map.of(1L, user(1)), service.get(List.of(1L, 2L)));
        var order = inOrder(reader, cache);
        order.verify(reader).load(List.of(1L, 2L));
        order.verify(cache).publish(1, "a", user(1));
        order.verify(cache).release(1, "a");
        order.verify(cache).missing(2, "b");
        order.verify(cache).release(2, "b");
    }

    @Test void secondCheckHitIsReleasedBeforeRemainingSql() {
        when(cache.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss(), 2L, CacheValue.miss()),
                Map.of(1L, CacheValue.hit(user(1)), 2L, CacheValue.miss()));
        when(cache.tryLock(anyLong())).thenReturn("token");
        when(reader.load(List.of(2L))).thenReturn(Map.of(2L, user(2)));
        when(cache.publish(2, "token", user(2))).thenReturn(true);
        assertEquals(2, service.get(List.of(1L, 2L)).size());
        var order = inOrder(cache, reader);
        order.verify(cache).release(1, "token");
        order.verify(reader).load(List.of(2L));
        verify(cache, times(1)).release(1, "token");
    }

    @Test void rejectedPublicationIsNotReturnedAndFailureDoesNotCreateNegative() {
        var newer = new UserBasicData(1, "updated", "avatar", "简介");
        when(cache.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss()), Map.of(1L, CacheValue.miss()),
                Map.of(1L, CacheValue.hit(newer)));
        when(cache.tryLock(1)).thenReturn("expired");
        when(reader.load(List.of(1L))).thenReturn(Map.of(1L, user(1)));
        assertEquals(newer, service.get(1).orElseThrow());
        verify(cache).release(1, "expired");
        verify(reader, times(1)).load(anyCollection());

        reset(cache, reader);
        when(cache.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss()));
        when(cache.tryLock(1)).thenReturn("token");
        when(reader.load(anyCollection())).thenThrow(new IllegalStateException("SQL失败"));
        assertThrows(ApiException.class, () -> service.get(1));
        verify(cache).release(1, "token");
        verify(cache, never()).missing(anyLong(), anyString());
    }

    @Test void splitsLargeBatchesWithoutPerUserSql() {
        when(cache.get(anyCollection())).thenAnswer(call -> {
            Map<Long, CacheValue<UserBasicData>> values = new HashMap<>();
            for (long id : (Collection<Long>) call.getArgument(0)) { values.put(id, CacheValue.miss()); }
            return values;
        });
        when(cache.tryLock(anyLong())).thenReturn("token");
        when(cache.publish(anyLong(), anyString(), any())).thenReturn(true);
        when(reader.load(anyCollection())).thenAnswer(call -> {
            Map<Long, UserBasicData> values = new HashMap<>();
            Collection<Long> ids = call.getArgument(0);
            assertTrue(ids.size() <= 100);
            ids.forEach(id -> values.put(id, user(id)));
            return values;
        });
        assertEquals(101, service.get(LongStream.rangeClosed(1, 101).boxed().toList()).size());
        verify(reader, times(2)).load(anyCollection());
    }

    @Test void lockContentionIsBoundedAndNeverBypassesSqlProtection() {
        when(cache.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss()));
        assertTimeout(java.time.Duration.ofSeconds(2), () -> assertThrows(ApiException.class, () -> service.get(1)));
        verify(cache).record("contention_timeout");
        verifyNoInteractions(reader);
        verify(cache, never()).missing(anyLong(), anyString());
    }

    @Test void limitsSourcesBeforeCompetingForAnotherLockAndRecoversSlots() throws Exception {
        when(cache.get(anyCollection())).thenAnswer(call -> {
            long id = ((Collection<Long>) call.getArgument(0)).iterator().next();
            return Map.of(id, CacheValue.miss());
        });
        when(cache.tryLock(anyLong())).thenReturn("token");
        when(cache.publish(anyLong(), anyString(), any())).thenReturn(true);
        var entered = new CountDownLatch(4); var proceed = new CountDownLatch(1);
        when(reader.load(anyCollection())).thenAnswer(call -> {
            entered.countDown();
            if (!proceed.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("测试等待超时"); }
            long id = ((Collection<Long>) call.getArgument(0)).iterator().next();
            return Map.of(id, user(id));
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (long id = 1; id <= 4; id++) { long userId = id; futures.add(executor.submit(() -> service.get(userId))); }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(ApiException.class, () -> service.get(5));
                verify(cache, never()).tryLock(5);
            } finally { proceed.countDown(); }
            for (var future : futures) { future.get(5, TimeUnit.SECONDS); }
        } finally { proceed.countDown(); }
        assertTrue(service.get(6).isPresent());
    }

    private static UserBasicData user(long id) { return new UserBasicData(id, "user" + id, null, "简介"); }
}
