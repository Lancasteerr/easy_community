package com.febrie.backend.article.like;

import com.febrie.backend.article.like.application.LikeSqlService;
import com.febrie.backend.article.like.domain.LikeState;
import com.febrie.backend.article.like.infrastructure.redis.LikeCache;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 不执行Redis交互，专门验证文章组失败隔离和计数缓存失效次数。 */
class LikeCacheTests {
    @Test void oneStateFailureStillMaintainsOtherUsersAndInvalidatesCountOnce() {
        var redis = mock(StringRedisTemplate.class);
        var cache = spy(new LikeCache(redis, LikeTestProperties.defaults()));
        doThrow(new IllegalStateException("first")).when(cache).putStates(eq(1L), anyCollection(), eq("C"));
        doReturn(Map.of()).when(cache).putStates(eq(2L), anyCollection(), eq("C"));
        var article = new LikeSqlService.ArticleResult(2, List.of(result(1, LikeSqlService.Status.APPLIED),
                result(2, LikeSqlService.Status.DUPLICATE), result(3, LikeSqlService.Status.STALE)), 0);
        assertThrows(IllegalStateException.class, () -> cache.committedArticle(article));
        verify(cache).putStates(eq(2L), anyCollection(), eq("C"));
        verify(cache, never()).putStates(eq(3L), anyCollection(), anyString());
        verify(redis, times(1)).delete(cache.countKey(2));
    }

    @Test void allStaleSkipsCacheAndSingleEntryReusesArticleProjection() {
        var redis = mock(StringRedisTemplate.class);
        var cache = spy(new LikeCache(redis, LikeTestProperties.defaults()));
        cache.committed(result(1, LikeSqlService.Status.STALE));
        verifyNoInteractions(redis);
        doReturn(Map.of()).when(cache).putStates(eq(1L), anyCollection(), eq("C"));
        cache.committed(result(1, LikeSqlService.Status.APPLIED));
        verify(redis).delete(cache.countKey(2));
    }

    private static LikeSqlService.Result result(long user, LikeSqlService.Status status) {
        return new LikeSqlService.Result(status, new LikeState(user, 2, true, 10));
    }
}
