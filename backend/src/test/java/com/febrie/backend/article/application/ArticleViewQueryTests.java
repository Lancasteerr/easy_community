package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.*;
import com.febrie.backend.article.infrastructure.cache.*;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 详情外层统一计数，缓存分支与列表、编辑都不能自行增加PV。 */
class ArticleViewQueryTests {
    private final ArticleReader reader = mock(ArticleReader.class);
    private final ArticleCache cache = mock(ArticleCache.class);
    private final ArticleDetailCacheStore details = mock(ArticleDetailCacheStore.class);
    private final ArticleListHydrator lists = mock(ArticleListHydrator.class);
    private final ArticleViewService views = mock(ArticleViewService.class);
    private final ArticleQueryService queries = new ArticleQueryService(reader, cache, details, lists, views);
    private final ArticleDetailView content = new ArticleDetailView("1", "title", "2", "author", "summary", null,
            null, List.of(), Instant.EPOCH, Instant.EPOCH, "<p>text</p>", null);

    @Test void cacheHitCountsOnceAndReturnsNewDto() {
        when(details.get(1)).thenReturn(CacheValue.hit(content));
        when(views.record(1)).thenReturn(101L);
        assertEquals(101L, queries.detail(1).viewCount());
        assertNull(content.viewCount());
        verify(views, times(1)).record(1);
        verifyNoInteractions(reader);
    }

    @Test void missAndSecondCacheCheckStillCountOnce() {
        when(details.get(1)).thenReturn(CacheValue.miss(), CacheValue.hit(content));
        when(cache.tryLock(1)).thenReturn("token");
        when(views.record(1)).thenReturn(2L);
        assertEquals(2L, queries.detail(1).viewCount());
        verify(views, times(1)).record(1);
        verify(cache).release(1, "token");
    }

    @Test void loadingContentCountsOnlyAfterItIsVisible() {
        when(details.get(1)).thenReturn(CacheValue.miss());
        when(cache.tryLock(1)).thenReturn("token");
        when(reader.detail(1)).thenReturn(LoadedArticle.visible(content));
        when(views.record(1)).thenReturn(1L);
        assertEquals(1L, queries.detail(1).viewCount());
        var order = inOrder(reader, cache, views);
        order.verify(reader).detail(1);
        order.verify(cache).release(1, "token");
        order.verify(views).record(1);
    }

    @Test void hiddenAndMissingDetailsNeverCount() {
        when(cache.hidden(1)).thenReturn(true);
        assertThrows(ApiException.class, () -> queries.detail(1));
        when(details.get(2)).thenReturn(CacheValue.negative());
        assertThrows(ApiException.class, () -> queries.detail(2));
        when(details.get(3)).thenReturn(CacheValue.miss());
        when(cache.tryLock(3)).thenReturn("token");
        when(reader.detail(3)).thenReturn(LoadedArticle.hidden());
        assertThrows(ApiException.class, () -> queries.detail(3));
        verifyNoInteractions(views);
    }

    @Test void listsHydrateCountsInOneBatchAndEditingDoesNotCount() {
        var actor = new UserActor(2, Role.USER, 1);
        var list = new ArticleListView("1", "title", "2", "author", "summary", null,
                List.of(), Instant.EPOCH, Instant.EPOCH, null);
        when(reader.page(2, 1, 20)).thenReturn(new ArticlePageIds(List.of(1L), 1));
        when(lists.authorPage(2, List.of(1L))).thenReturn(List.of(list));
        when(views.totals(List.of(1L))).thenReturn(Map.of(1L, 7L));
        assertEquals(7L, queries.page(actor, 1, 20).items().getFirst().viewCount());
        queries.edit(actor, 1);
        verify(views).totals(List.of(1L));
        verify(views, never()).record(anyLong());
    }
}
