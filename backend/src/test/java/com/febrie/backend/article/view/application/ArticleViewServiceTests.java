package com.febrie.backend.article.view.application;

import com.febrie.backend.article.common.ArticleErrors;

import com.febrie.backend.article.view.infrastructure.cache.ArticleViewStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 查询基线只用于确认缺失；未知计数结果不自动重试。 */
class ArticleViewServiceTests {
    private final ArticleViewStore store = mock(ArticleViewStore.class);
    private final ArticleViewReader reader = mock(ArticleViewReader.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T16:00:00Z"), ZoneOffset.UTC);
    private final ArticleViewService service = new ArticleViewService(store, reader, clock);
    private final LocalDate date = LocalDate.of(2026, 11, 2);

    @Test void existingTotalDoesNotQuerySqlAndUsesBusinessDate() {
        when(store.increment(1, date, null)).thenReturn(101L);
        assertEquals(101, service.record(1));
        verifyNoInteractions(reader);
    }

    @Test void confirmedMissingBaselineCanInitializeOnce() {
        when(store.increment(1, date, null)).thenReturn(null);
        when(reader.total(1)).thenReturn(100L);
        when(store.increment(1, date, 100L)).thenReturn(101L);
        assertEquals(101, service.record(1));
        var order = inOrder(store, reader);
        order.verify(store).increment(1, date, null);
        order.verify(reader).total(1);
        order.verify(store).increment(1, date, 100L);
    }

    @Test void unknownResultFallsBackWithoutResendingIncrement() {
        when(store.increment(1, date, null)).thenThrow(new IllegalStateException("执行结果未知"));
        when(reader.total(1)).thenReturn(90L);
        assertEquals(90, service.record(1));
        verify(store, times(1)).increment(anyLong(), any(), any());
    }

    @Test void failureAfterBaselineDoesNotRetryTheUnknownIncrement() {
        when(store.increment(1, date, null)).thenReturn(null);
        when(reader.total(1)).thenReturn(100L);
        when(store.increment(1, date, 100L)).thenThrow(new IllegalStateException("初始化计数结果未知"));
        assertEquals(100, service.record(1));
        verify(store, times(1)).increment(1, date, 100L);
    }

    @Test void sqlBaselineFailurePropagatesWithoutRetryOrIncrement() {
        when(store.increment(1, date, null)).thenReturn(null);
        when(reader.total(1)).thenThrow(ArticleErrors.unavailable());
        assertThrows(com.febrie.backend.shared.error.ApiException.class, () -> service.record(1));
        verify(reader, times(1)).total(1);
        verify(store, times(1)).increment(anyLong(), any(), any());
    }

    @Test void listsOnlyQueryMissingBaselinesInOneBatch() {
        when(store.totals(List.of(1L, 2L, 3L))).thenReturn(Map.of(1L, 20L));
        when(reader.totals(List.of(2L, 3L))).thenReturn(Map.of(2L, 10L, 3L, 0L));
        when(store.initialize(Map.of(2L, 10L, 3L, 0L))).thenReturn(Map.of(2L, 11L, 3L, 0L));
        assertEquals(Map.of(1L, 20L, 2L, 11L, 3L, 0L), service.totals(List.of(1L, 2L, 3L)));
        verify(store, never()).increment(anyLong(), any(), any());
        verify(reader, times(1)).totals(anyCollection());
    }

    @Test void redisReadAndInitializationFailuresUseSqlWithoutInventingZero() {
        when(store.totals(List.of(1L))).thenThrow(new IllegalStateException("Redis不可用"));
        when(reader.totals(List.of(1L))).thenReturn(Map.of(1L, 12L));
        assertEquals(Map.of(1L, 12L), service.totals(List.of(1L)));
        when(store.totals(List.of(2L))).thenReturn(Map.of());
        when(reader.totals(List.of(2L))).thenReturn(Map.of(2L, 10L));
        when(store.initialize(Map.of(2L, 10L))).thenThrow(new IllegalStateException("初始化结果未知"));
        assertEquals(Map.of(2L, 10L), service.totals(List.of(2L)));
        when(reader.totals(List.of(1L))).thenThrow(ArticleErrors.unavailable());
        assertThrows(com.febrie.backend.shared.error.ApiException.class, () -> service.totals(List.of(1L)));
    }

    @Test void emptyListDoesNotAccessDependencies() {
        assertEquals(Map.of(), service.totals(List.of()));
        verifyNoInteractions(store, reader);
    }
}
