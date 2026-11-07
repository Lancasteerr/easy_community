package com.febrie.backend.article.search;

import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.infrastructure.elasticsearch.ArticleRankStore;
import com.febrie.backend.article.search.infrastructure.persistence.mapper.ArticleIndexMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 常规刷新只读取三个日期候选；SQL失败不能转成零浏览写入ES。 */
class ArticleRankServiceTests {
    @Test void normalRefreshDoesNotScanAllArticles() {
        var mapper = mock(ArticleIndexMapper.class); var reader = mock(ArticleRankReader.class); var store = mock(ArticleRankStore.class);
        var p = SearchTestSupport.properties(Map.of());
        var clock = Clock.fixed(Instant.parse("2026-11-08T00:00:00Z"), ZoneOffset.UTC);
        var service = new ArticleRankService(mapper, reader, store, p, clock, new SimpleMeterRegistry());
        LocalDate day = LocalDate.of(2026, 11, 8);
        when(mapper.maxId()).thenReturn(10L);
        when(mapper.candidates(day, 0, 10)).thenReturn(List.of(1L));
        when(reader.load(List.of(1L), day)).thenReturn(List.of(new ArticleIndexMapper.RankRow(1, clock.instant(), 20)));
        when(store.write(anyString(), eq(true), anyList())).thenReturn(new ArticleRankStore.WriteResult(0, 0));
        try {
            service.scan(false, p.writeAlias(), true);
            verify(mapper).candidates(day.minusDays(1), 0, 10); verify(mapper).candidates(day.minusDays(7), 0, 10);
            verify(mapper, never()).scanIds(anyLong(), anyLong(), anyBoolean());
            verify(reader).load(List.of(1L), day);
            reset(store); when(reader.load(List.of(1L), day)).thenThrow(new IllegalStateException("SQL失败"));
            assertThrows(IllegalStateException.class, () -> service.scan(false, p.writeAlias(), true));
            verifyNoInteractions(store);
        } finally { service.destroy(); }
    }
    @Test void formulaHasStableTimePreference() {
        Instant newer = Instant.parse("2026-11-08T00:00:00Z");
        assertEquals(RankFormula.score(9, newer), RankFormula.score(19, newer.minus(Duration.ofHours(48))), 1e-10);
        assertTrue(RankFormula.score(0, newer) > RankFormula.score(0, newer.minusSeconds(3600)));
    }
}
