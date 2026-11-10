package com.febrie.backend.article.search;

import com.febrie.backend.article.content.application.dto.*;
import com.febrie.backend.article.content.application.ArticleDisplayAssembler;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.search.application.*;
import com.febrie.backend.article.search.web.*;
import com.febrie.backend.article.view.application.ArticleViewService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 静态路由与扁平卡片协议验证；完整Session安全链由现有HTTP集成测试回归。 */
class ArticleSearchHttpTests {
    @Test void feedUsesRecommendedQueryAndDoesNotRecordViews() throws Exception {
        var service = mock(ArticleSearchService.class);
        var display = mock(ArticleDisplayAssembler.class);
        var views = mock(ArticleViewService.class); var likes = mock(LikeQueryService.class);
        when(service.page(new ArticleSearchQuery("feed", null, null, "recommended", 20), null))
                .thenReturn(new ArticleSearchService.Page(List.of(), null));
        when(views.totals(List.of())).thenReturn(Map.of()); when(likes.summaries(null, List.of())).thenReturn(Map.of());
        var mvc = MockMvcBuilders.standaloneSetup(new ArticleSearchController(service, new ArticleSearchAssembler(views, likes, display)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/articles/feed")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        verify(service).page(new ArticleSearchQuery("feed", null, null, "recommended", 20), null);
        verify(views, never()).record(anyLong());
    }
    @Test void guestGetsFlatCardWithOnlyFinalItemCounts() throws Exception {
        var service = mock(ArticleSearchService.class);
        var display = mock(ArticleDisplayAssembler.class);
        var views = mock(ArticleViewService.class); var likes = mock(LikeQueryService.class);
        var card = new ArticleListView("1", "Java", "2", "author", "summary", null, List.of(), Instant.EPOCH, Instant.EPOCH, "https://avatars.test/2");
        when(service.page(new ArticleSearchQuery("search", "Java", null, null, 20), null))
                .thenReturn(new ArticleSearchService.Page(List.of(new ArticleBriefData("1", "Java", "2", "summary", null, List.of(), Instant.EPOCH, Instant.EPOCH)), null));
        when(display.lists(anyList())).thenReturn(List.of(card));
        when(views.totals(List.of(1L))).thenReturn(Map.of(1L, 10L));
        when(likes.summaries(null, List.of(1L))).thenReturn(Map.of(1L, new LikeQueryService.Summary(3, false)));
        var mvc = MockMvcBuilders.standaloneSetup(new ArticleSearchController(service, new ArticleSearchAssembler(views, likes, display)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        mvc.perform(get("/api/articles/search").param("q", "Java"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].articleId").value("1"))
                .andExpect(jsonPath("$.items[0].authorName").value("author"))
                .andExpect(jsonPath("$.items[0].authorAvatarUrl").value("https://avatars.test/2"))
                .andExpect(jsonPath("$.items[0].viewCount").value(10))
                .andExpect(jsonPath("$.items[0].likeCount").value(3))
                .andExpect(jsonPath("$.items[0].likedByMe").value(false));
        verify(views).totals(List.of(1L)); verify(likes).summaries(null, List.of(1L));
    }
}
