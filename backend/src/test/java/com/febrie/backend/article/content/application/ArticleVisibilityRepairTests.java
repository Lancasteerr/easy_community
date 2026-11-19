package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.content.infrastructure.scheduling.ArticleVisibilityRepairTask;
import com.febrie.backend.shared.cache.CacheMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import static org.mockito.Mockito.*;

/** 直接调用维护入口，验证Redis结果与待办清理顺序。 */
class ArticleVisibilityRepairTests {
    private final ArticleCache cache = mock(ArticleCache.class);
    private final ArticleVisibilityRepairs repairs = mock(ArticleVisibilityRepairs.class);
    private final ArticleCacheMaintenance maintenance = new ArticleCacheMaintenance(cache, repairs);
    private final CacheMetrics metrics = mock(CacheMetrics.class);
    private final ArticleVisibilityRepairTask task = new ArticleVisibilityRepairTask(repairs, maintenance, metrics);

    @Test void afterCommitWritesAndUnlocksBeforeCompleting() {
        when(cache.tryLock(1)).thenReturn("token");
        when(cache.hide(1, "token")).thenReturn(true);

        maintenance.afterCommit(1, true);

        var order = inOrder(cache, repairs);
        order.verify(cache).hide(1, "token");
        order.verify(cache).release(1, "token");
        order.verify(repairs).complete(1);
    }

    @Test void repairLeavesBusyAndFailedItemsForRetryAndContinuesBatch() {
        when(repairs.claim()).thenReturn(List.of(1L, 2L, 3L, 4L));
        when(cache.tryLock(2)).thenReturn("redis-failed");
        when(cache.hide(2, "redis-failed")).thenThrow(new RedisConnectionFailureException("模拟Redis失败"));
        when(cache.tryLock(3)).thenReturn("expired");
        when(cache.tryLock(4)).thenReturn("healthy");
        when(cache.hide(4, "healthy")).thenReturn(true);

        task.repair();

        verify(repairs).claim();
        verify(repairs).complete(4);
        verifyNoMoreInteractions(repairs);
        verify(cache).release(2, "redis-failed");
        verify(cache).release(3, "expired");
        verify(metrics, times(2)).record("article", "visibility_repair_busy");
        verify(metrics).record("article", "visibility_repair_failed");
        verify(metrics).record("article", "visibility_repair_completed");
    }

    @Test void failedDeletionAfterCommitIsRetriedIdempotentlyByTask() {
        when(cache.tryLock(1)).thenReturn("foreground", "background");
        when(cache.hide(eq(1L), anyString())).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("模拟待办清理失败")).doNothing().when(repairs).complete(1);
        when(repairs.claim()).thenReturn(List.of(1L));

        maintenance.afterCommit(1, true);
        task.repair();

        verify(cache).hide(1, "foreground");
        verify(cache).hide(1, "background");
        verify(repairs, times(2)).complete(1);
        verify(cache).record("visibility_repair_cleanup_failed");
        verify(metrics).record("article", "visibility_repair_completed");
    }

}
