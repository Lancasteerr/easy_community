package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.application.dto.ArticleListView;
import com.febrie.backend.article.content.application.dto.LoadedArticle;
import com.febrie.backend.article.content.infrastructure.cache.ArticleCache;
import com.febrie.backend.article.content.infrastructure.cache.ArticleListCacheStore;
import com.febrie.backend.article.content.infrastructure.cache.CacheValue;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证批量装配边界；实际token原子行为由Redis集成测试覆盖。 */
class ArticleListHydratorTests {
    private final ArticleCache cache = mock(ArticleCache.class);
    private final ArticleListCacheStore lists = mock(ArticleListCacheStore.class);
    private final ArticleReader reader = mock(ArticleReader.class);
    private final ArticleListHydrator hydrator = new ArticleListHydrator(cache, lists, reader);
    @BeforeEach void noHidden() {
        when(cache.hidden(anyCollection())).thenAnswer(call -> {
            Map<Long, Boolean> result = new HashMap<>();
            for (long id : (Collection<Long>) call.getArgument(0)) { result.put(id, false); }
            return result;
        });
    }
    @Test void warmBatchKeepsInputOrderWithoutSql() {
        when(lists.get(anyCollection())).thenReturn(Map.of(2L, CacheValue.hit(view(2)), 1L, CacheValue.hit(view(1))));
        assertEquals(List.of("1", "2"), hydrator.authorPage(9, List.of(1L, 2L)).stream().map(ArticleListView::articleId).toList());
        verifyNoInteractions(reader);
        verify(cache, never()).tryLock(anyLong());
    }
    @Test void mergesMissesIntoOneSqlAndChecksEachTokenEvenIfPublishRejected() {
        when(lists.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss(), 2L, CacheValue.miss()));
        when(cache.tryLock(1)).thenReturn("a"); when(cache.tryLock(2)).thenReturn("b");
        when(reader.lists(List.of(1L, 2L))).thenReturn(Map.of(2L, LoadedArticle.visible(view(2)), 1L, LoadedArticle.visible(view(1))));
        assertEquals(2, hydrator.authorPage(9, List.of(1L, 2L)).size());
        verify(reader, times(1)).lists(List.of(1L, 2L));
        verify(lists).publish(1, "a", view(1)); verify(lists).publish(2, "b", view(2));
        verify(cache).release(1, "a"); verify(cache).release(2, "b");
    }
    @Test void negativeConflictUsesSqlAndOnlyConfirmedHiddenDisappears() {
        when(lists.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.negative(), 2L, CacheValue.negative()));
        when(reader.lists(anyCollection())).thenReturn(Map.of(1L, LoadedArticle.visible(view(1)), 2L, LoadedArticle.hidden()));
        assertEquals(List.of(view(1)), hydrator.authorPage(9, List.of(1L, 2L)));
        verify(lists, never()).publish(anyLong(), anyString(), any());
        verify(cache, never()).tryLock(anyLong());
    }
    @Test void dependencyFailureDoesNotBecomeMissingAndStillReleasesLocks() {
        when(lists.get(anyCollection())).thenReturn(Map.of(1L, CacheValue.miss()));
        when(cache.tryLock(1)).thenReturn("a");
        when(reader.lists(anyCollection())).thenThrow(new IllegalStateException("作者读取失败"));
        assertThrows(IllegalStateException.class, () -> hydrator.authorPage(9, List.of(1L)));
        verify(cache).release(1, "a");
        verify(cache, never()).missing(anyLong(), anyString());
        verify(lists, never()).publish(anyLong(), anyString(), any());
    }
    private static ArticleListView view(long id) {
        return new ArticleListView(Long.toString(id), "title", "9", "author", "summary", null, List.of(), Instant.EPOCH, Instant.EPOCH);
    }
}
