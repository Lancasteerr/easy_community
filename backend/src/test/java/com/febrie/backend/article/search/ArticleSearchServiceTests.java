package com.febrie.backend.article.search;

import com.febrie.backend.article.content.application.ArticleListHydrator;
import com.febrie.backend.article.content.application.dto.ArticleBriefData;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleSearchIndex;
import com.febrie.backend.shared.error.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 游标只经过已处理候选，补齐有界；依赖失败不伪造不存在。 */
class ArticleSearchServiceTests {
    private final ArticleSearchIndex index = mock(ArticleSearchIndex.class);
    private final ArticleListHydrator cards = mock(ArticleListHydrator.class);
    private final com.febrie.backend.article.search.infrastructure.config.SearchProperties p = SearchTestSupport.properties(Map.of(
            "app.search.query-enabled", true, "app.search.cursor-secret", "test-secret-32-bytes-abcdefghijklmnop"));
    private final SearchCursorCodec cursors = new SearchCursorCodec(new ObjectMapper(), p, Clock.systemUTC());
    private final ArticleSearchService service = new ArticleSearchService(p, index, cards, cursors, new SimpleMeterRegistry());
    private final ArticleSearchQuery query = new ArticleSearchQuery("search", "Java", null, null, 20);
    @Test void lastProcessedHitIs23InsteadOfPrefetched30() throws Exception {
        when(index.open()).thenReturn("pit0");
        when(index.search(eq(query), eq("pit0"), eq(List.of()))).thenReturn(batch("pit1", 1, 30));
        when(cards.publicCandidates(anyList())).thenAnswer(call -> ((List<Long>) call.getArgument(0)).stream()
                .filter(id -> id > 3).map(ArticleSearchServiceTests::card).toList());
        var first = service.page(query, null);
        assertEquals("4", first.items().getFirst().articleId());
        assertEquals("23", first.items().getLast().articleId());
        var cursor = cursors.decode(first.nextCursor(), query);
        assertEquals("pit1", cursor.pit()); assertEquals("23", cursor.sort().getFirst().value());
        when(index.search(query, "pit1", cursor.sort())).thenReturn(batch("pit2", 24, 40));
        var next = service.page(query, first.nextCursor());
        assertEquals("24", next.items().getFirst().articleId()); assertNull(next.nextCursor());
        verify(index).close("pit2");
    }
    @Test void invalidCardsCanProduceEmptyPageWithContinuationAfterThreeRounds() throws Exception {
        when(index.open()).thenReturn("pit");
        when(index.search(eq(query), eq("pit"), anyList())).thenReturn(batch("pit", 1, 30), batch("pit", 31, 60), batch("pit", 61, 90));
        when(cards.publicCandidates(anyList())).thenReturn(List.of());
        var page = service.page(query, null);
        assertTrue(page.items().isEmpty());
        assertEquals("90", cursors.decode(page.nextCursor(), query).sort().getFirst().value());
        verify(index, times(3)).search(eq(query), eq("pit"), anyList());
    }
    @Test void dependencyErrorFailsWholePageWithoutPublishingContinuation() throws Exception {
        when(index.open()).thenReturn("pit");
        when(index.search(eq(query), eq("pit"), anyList())).thenReturn(batch("pit", 1, 30));
        when(cards.publicCandidates(anyList())).thenThrow(new IllegalStateException("SQL超时"));
        assertEquals(503, assertThrows(ApiException.class, () -> service.page(query, null)).status().value());
        verify(index).close("pit");
    }
    private static ArticleSearchIndex.Batch batch(String pit, long from, long to) {
        return new ArticleSearchIndex.Batch(pit, LongStream.rangeClosed(from, to).mapToObj(id -> new ArticleSearchIndex.Hit(id,
                List.of(new SearchCursor.Value("long", Long.toString(id))))).toList());
    }
    private static ArticleBriefData card(long id) {
        return new ArticleBriefData(Long.toString(id), "title", "1", "summary", null, List.of(), Instant.EPOCH, Instant.EPOCH);
    }
}
