package com.febrie.backend.article.application;

import com.febrie.backend.article.infrastructure.cache.ArticleViewStore;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/** 顺序证明提交异常时不执行修复，另一日期仍可刷盘。 */
class ArticleViewFlushTests {
    @Test void failedYesterdayTransactionDoesNotRepairAndTodayStillRuns() {
        var store = mock(ArticleViewStore.class);
        var sql = mock(ArticleViewSqlService.class);
        var today = LocalDate.of(2026, 11, 2);
        var snapshot = Map.of(1L, 10L);
        var result = new ArticleViewSqlService.FlushResult(Map.of(), Map.of(1L, 20L));
        when(store.daily(today.minusDays(1))).thenReturn(snapshot);
        when(store.daily(today)).thenReturn(snapshot);
        when(sql.flush(today.minusDays(1), snapshot)).thenThrow(new IllegalStateException("提交失败"));
        when(sql.flush(today, snapshot)).thenReturn(result);
        new ArticleViewFlush(store, sql, Clock.fixed(Instant.parse("2026-11-01T16:00:00Z"), ZoneOffset.UTC)).flush();
        verify(store, never()).repair(eq(today.minusDays(1)), any());
        var order = inOrder(store, sql);
        order.verify(store).daily(today.minusDays(1));
        order.verify(sql).flush(today.minusDays(1), snapshot);
        order.verify(store).daily(today);
        order.verify(sql).flush(today, snapshot);
        order.verify(store).repair(today, result);
    }
}
