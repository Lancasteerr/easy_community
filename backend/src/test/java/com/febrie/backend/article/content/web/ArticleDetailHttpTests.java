package com.febrie.backend.article.content.web;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.content.application.ArticleQueryService;
import com.febrie.backend.article.content.application.ArticleService;
import com.febrie.backend.article.content.application.dto.ArticleDetailView;
import com.febrie.backend.article.content.web.response.ArticleDetailResponseAssembler;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.view.application.ArticleViewService;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.shared.web.ApiExceptionHandler;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 验证最终详情协议与先读后计边界；真实Session及内容缓存由HTTP集成测试覆盖。 */
class ArticleDetailHttpTests {
    private final ArticleQueryService queries = mock(ArticleQueryService.class);
    private final LikeQueryService likes = mock(LikeQueryService.class);
    private final ArticleViewService views = mock(ArticleViewService.class);
    private MockMvc mvc;

    @BeforeEach void prepare() {
        SecurityContextHolder.clearContext();
        var controller = new ArticleController(mock(ArticleService.class), queries, new ObjectMapper(),
                new ArticleDetailResponseAssembler(likes, views), views);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        when(queries.detail(1)).thenReturn(new ArticleDetailView("1", "Java", "2", "author", "summary",
                "cover-id", "https://images.test/cover", List.of(), Instant.EPOCH, Instant.EPOCH,
                "<p>正文</p>", "https://avatars.test/2"));
    }

    @AfterEach void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test void guestGetsFlatCountsBeforeRecordingCurrentView() throws Exception {
        when(likes.summaries(null, List.of(1L))).thenReturn(Map.of(1L, new LikeQueryService.Summary(3, false)));
        when(views.totals(List.of(1L))).thenReturn(Map.of(1L, 10L));
        // 加一返回11，但响应应使用此前读取的10，不能用计数结果覆盖。
        when(views.record(1)).thenReturn(11L);

        expectArticle(mvc.perform(get("/api/articles/1")))
                .andExpect(jsonPath("$.viewCount").value(10))
                .andExpect(jsonPath("$.likeCount").value(3))
                .andExpect(jsonPath("$.likedByMe").value(false));

        var order = inOrder(queries, likes, views);
        order.verify(queries).detail(1);
        order.verify(likes).summaries(null, List.of(1L));
        order.verify(views).totals(List.of(1L));
        order.verify(views).record(1);
        order.verifyNoMoreInteractions();
    }

    @Test void loggedInReaderGetsOwnLikeStateAndZeroViewCount() throws Exception {
        var principal = new SessionPrincipal(9L, "reader", Role.USER, 1L, Instant.EPOCH, Instant.MAX);
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(principal, null));
        when(likes.summaries(9L, List.of(1L))).thenReturn(Map.of(1L, new LikeQueryService.Summary(1, true)));
        when(views.totals(List.of(1L))).thenReturn(Map.of(1L, 0L));

        expectArticle(mvc.perform(get("/api/articles/1")))
                .andExpect(jsonPath("$.viewCount").value(0))
                .andExpect(jsonPath("$.likeCount").value(1))
                .andExpect(jsonPath("$.likedByMe").value(true));
        verify(likes).summaries(9L, List.of(1L));
        verify(views).record(1);
    }

    @Test void missingArticleDoesNotReadStatsOrRecordView() throws Exception {
        when(queries.detail(1)).thenThrow(ArticleErrors.notFound());

        mvc.perform(get("/api/articles/1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        verifyNoInteractions(likes, views);
    }

    @Test void unavailableLikesDoNotRecordView() throws Exception {
        when(likes.summaries(null, List.of(1L))).thenThrow(ArticleErrors.unavailable());

        mvc.perform(get("/api/articles/1")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        verifyNoInteractions(views);
    }

    @Test void unavailableViewCountDoesNotInventZeroOrRecordView() throws Exception {
        when(likes.summaries(null, List.of(1L))).thenReturn(Map.of(1L, new LikeQueryService.Summary(3, false)));
        when(views.totals(List.of(1L))).thenThrow(ArticleErrors.unavailable());

        mvc.perform(get("/api/articles/1")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.viewCount").doesNotExist());
        verify(views, never()).record(anyLong());
    }

    @Test void unknownIncrementResultKeepsAssembledResponseWithoutRetry() throws Exception {
        when(likes.summaries(null, List.of(1L))).thenReturn(Map.of(1L, new LikeQueryService.Summary(3, false)));
        when(views.totals(List.of(1L))).thenReturn(Map.of(1L, 10L));
        when(views.record(1)).thenThrow(new IllegalStateException("计数结果未知"));

        expectArticle(mvc.perform(get("/api/articles/1")))
                .andExpect(jsonPath("$.viewCount").value(10))
                .andExpect(jsonPath("$.likeCount").value(3))
                .andExpect(jsonPath("$.likedByMe").value(false));
        verify(views, times(1)).record(1);
        verify(views, times(1)).totals(List.of(1L));
    }

    /** 外层统计包装不得引入article层级，也不能丢失原文章展示字段。 */
    private ResultActions expectArticle(ResultActions response) throws Exception {
        return response.andExpect(status().isOk())
                .andExpect(jsonPath("$.article").doesNotExist())
                .andExpect(jsonPath("$.articleId").value("1"))
                .andExpect(jsonPath("$.title").value("Java"))
                .andExpect(jsonPath("$.authorId").value("2"))
                .andExpect(jsonPath("$.authorName").value("author"))
                .andExpect(jsonPath("$.summary").value("summary"))
                .andExpect(jsonPath("$.coverMediaId").value("cover-id"))
                .andExpect(jsonPath("$.coverUrl").value("https://images.test/cover"))
                .andExpect(jsonPath("$.tags").isArray())
                .andExpect(jsonPath("$.publishedAt").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.contentHtml").value("<p>正文</p>"))
                .andExpect(jsonPath("$.authorAvatarUrl").value("https://avatars.test/2"));
    }
}
