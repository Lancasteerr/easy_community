package com.febrie.backend.article.view.application;

import com.febrie.backend.article.view.infrastructure.persistence.mapper.ArticleViewStatMapper;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 差值与修复职责验证；事务提交、回滚另由真实MySQL验证。 */
class ArticleViewSqlServiceTests {
    private final ArticleViewStatMapper stats = mock(ArticleViewStatMapper.class);
    private final ArticleViewSqlService service = new ArticleViewSqlService(stats);
    private final LocalDate date = LocalDate.of(2026, 11, 2);

    @Test void flushesInIdOrderAndOnlyAddsPositiveDelta() {
        when(stats.selectPvForUpdate(1, date)).thenReturn(4L);
        when(stats.selectPvForUpdate(2, date)).thenReturn(8L);
        when(stats.selectPvForUpdate(3, date)).thenReturn(9L);
        when(stats.incrementTotal(1, 2)).thenReturn(1);
        when(stats.selectTotal(1)).thenReturn(20L);
        when(stats.selectTotal(2)).thenReturn(30L);
        when(stats.selectTotal(3)).thenReturn(40L);
        var result = service.flush(date, Map.of(3L, 2L, 1L, 6L, 2L, 8L));
        assertEquals(Map.of(3L, 9L), result.dailyFloors());
        assertEquals(Map.of(1L, 20L, 2L, 30L, 3L, 40L), result.totalFloors());
        var order = inOrder(stats);
        order.verify(stats).selectPvForUpdate(1, date);
        order.verify(stats).incrementTotal(1, 2);
        order.verify(stats).upsertPv(1, date, 6);
        order.verify(stats).selectTotal(1);
        order.verify(stats).selectPvForUpdate(2, date);
        order.verify(stats).selectTotal(2);
        order.verify(stats).selectPvForUpdate(3, date);
        verify(stats, times(1)).incrementTotal(anyLong(), anyLong());
        verify(stats, times(1)).upsertPv(anyLong(), any(), anyLong());
    }

    @Test void missingArticleDoesNotCreateOrphanStatistics() {
        assertTrue(service.flush(date, Map.of(1L, 2L)).totalFloors().isEmpty());
        verify(stats, never()).upsertPv(anyLong(), any(), anyLong());
        verify(stats, never()).selectTotal(anyLong());
    }

    @Test void sqlFailureEscapesAndDoesNotReturnRepairs() {
        when(stats.incrementTotal(1, 2)).thenReturn(1);
        doThrow(new IllegalStateException("注入日值写入故障")).when(stats).upsertPv(1, date, 2);
        assertThrows(IllegalStateException.class, () -> service.flush(date, Map.of(1L, 2L)));
        verify(stats, never()).selectTotal(anyLong());
    }
}
