package com.febrie.backend.article.content.infrastructure.cache;

import com.febrie.backend.article.content.application.ArticleListHydrator;
import com.febrie.backend.article.content.application.ArticleReader;
import com.febrie.backend.article.content.application.dto.ArticleBriefData;
import com.febrie.backend.article.content.infrastructure.config.ArticleCacheProperties;
import com.febrie.backend.shared.cache.CacheMetrics;
import com.febrie.backend.shared.error.ApiException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实缓存解析逻辑，确认可见性命中只有一次同槽读取。 */
class ArticleVisibilityCacheTests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ArticleReader reader = mock(ArticleReader.class);
    private final CacheMetrics metrics = mock(CacheMetrics.class);
    private final ArticleCache cache = new ArticleCache(redis, new ArticleCacheProperties("ec:test:visibility:article:v4"), metrics);
    private final ArticleListHydrator hydrator = new ArticleListHydrator(cache, new ArticleListCacheStore(cache, JSON), reader);
    private final List<String> keys = List.of(cache.key(1, "hidden"), cache.key(1, "list"));

    @ParameterizedTest
    @MethodSource("knownVisibility")
    void cachedVisibilityUsesOnlyOneRead(String tombstone, String list, boolean expected) {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(keys)).thenReturn(Arrays.asList(tombstone, list));

        assertEquals(expected, hydrator.isPublic(1));

        verify(redis).opsForValue();
        verify(values).multiGet(keys);
        verifyNoMoreInteractions(redis, values);
        verifyNoInteractions(reader);
        verify(metrics).record("article", expected ? "visibility_hit" : "visibility_negative_hit");
    }

    @Test void corruptProjectionAndRedisFailureAreUnavailableWithoutFallback() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(keys)).thenReturn(Arrays.asList(null, "D{broken"), Arrays.asList(null, "Dnull"))
                .thenThrow(new RedisConnectionFailureException("模拟Redis失败"));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class, () -> hydrator.isPublic(1)).code());
        }

        verify(redis, times(3)).opsForValue();
        verify(values, times(3)).multiGet(keys);
        verifyNoMoreInteractions(redis, values);
        verifyNoInteractions(reader);
    }

    private static Stream<Arguments> knownVisibility() {
        var article = new ArticleBriefData("1", "title", "2", "summary", null, List.of(), Instant.EPOCH, Instant.EPOCH);
        String positive = "D" + JSON.writeValueAsString(article);
        return Stream.of(Arguments.of(null, positive, true), Arguments.of("1", positive, false), Arguments.of(null, "M", false));
    }
}
