package com.febrie.backend.article.content.application;

import com.febrie.backend.article.content.application.dto.*;
import com.febrie.backend.article.content.infrastructure.cache.*;
import com.febrie.backend.shared.cache.CacheValue;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 作者组装及最终可见性检查必须发生在解锁之后。 */
class ArticleQueryServiceTests {
    private final ArticleReader reader = mock(ArticleReader.class);
    private final ArticleCache cache = mock(ArticleCache.class);
    private final ArticleDetailCacheStore details = mock(ArticleDetailCacheStore.class);
    private final ArticleDisplayAssembler display = mock(ArticleDisplayAssembler.class);
    private final ArticleQueryService service = new ArticleQueryService(reader, cache, details, display);

    @Test void unlocksBeforeVisibilityCheckAndAuthorAssembly() {
        when(details.get(1)).thenReturn(CacheValue.miss());
        when(cache.tryLock(1)).thenReturn("token");
        when(reader.detail(1)).thenReturn(LoadedArticle.visible(data("old")));
        when(details.publish(1, "token", data("old"))).thenReturn(true);
        service.detail(1);
        var order = inOrder(cache, display);
        order.verify(cache).release(1, "token");
        order.verify(cache).hidden(1);
        order.verify(display).detail(data("old"));
    }

    @Test void secondCheckHitUnlocksWithoutSqlAndWarmHitDoesNotLock() {
        when(details.get(1)).thenReturn(CacheValue.miss(), CacheValue.hit(data("new")));
        when(cache.tryLock(1)).thenReturn("token");
        service.detail(1);
        verifyNoInteractions(reader);
        var order = inOrder(cache, display);
        order.verify(cache).release(1, "token");
        order.verify(display).detail(data("new"));
        clearInvocations(cache, display);
        service.detail(1);
        verify(cache, never()).tryLock(anyLong());
    }

    @Test void rejectedTokenCannotReturnOldSourceData() {
        when(details.get(1)).thenReturn(CacheValue.miss(), CacheValue.miss(), CacheValue.hit(data("new")));
        when(cache.tryLock(1)).thenReturn("expired");
        when(reader.detail(1)).thenReturn(LoadedArticle.visible(data("old")));
        service.detail(1);
        verify(display).detail(data("new"));
        verify(display, never()).detail(data("old"));
        verify(cache).release(1, "expired");
    }

    @Test void fiveDifferentColdDetailsCanLoadConcurrently() throws Exception {
        when(details.get(anyLong())).thenReturn(CacheValue.miss());
        when(cache.tryLock(anyLong())).thenReturn("token");
        when(details.publish(anyLong(), eq("token"), any())).thenReturn(true);
        var entered = new CountDownLatch(5);
        var release = new CountDownLatch(1);
        when(reader.detail(anyLong())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "详情回源释放超时");
            return LoadedArticle.visible(data("cold"));
        });
        try (var executor = Executors.newFixedThreadPool(5)) {
            var requests = new ArrayList<Future<?>>();
            try {
                for (long id = 1; id <= 5; id++) {
                    long articleId = id;
                    requests.add(executor.submit(() -> service.detail(articleId)));
                }
                // 先确认五篇文章都开始回源，再放行模拟的主库查询。
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
            for (var request : requests) { request.get(5, TimeUnit.SECONDS); }
        }
        verify(display, times(5)).detail(data("cold"));
    }

    private static ArticleDetailData data(String title) {
        return new ArticleDetailData("1", title, "2", null, null, List.of(), Instant.EPOCH, Instant.EPOCH, "<p>x</p>");
    }
}
