package com.febrie.backend.article.comment.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.command.CommentSubmissions;
import com.febrie.backend.article.comment.application.command.CommentWriteService;
import com.febrie.backend.article.comment.application.query.CommentCountQuery;
import com.febrie.backend.article.comment.application.query.CommentListKey;
import com.febrie.backend.article.comment.application.query.CommentLists;
import com.febrie.backend.article.comment.application.query.CommentRootLists;
import com.febrie.backend.article.like.application.LikeWriteService;
import com.febrie.backend.shared.web.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证旧客户端多传提交编号时，游客仍能按游标读取回复。 */
class CommentRepliesHttpTests {
    @Test
    void guestUsesOrdinaryPaginationWithObsoleteSubmissionParameter() throws Exception {
        var lists = mock(CommentLists.class);
        var controller = new CommentController(lists, mock(CommentWriteService.class), mock(CommentAccess.class),
                mock(LikeWriteService.class), mock(CommentRootLists.class), mock(CommentCountQuery.class),
                mock(CommentSubmissions.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var key = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        when(lists.page(key, 10, "previous", null)).thenReturn(new CommentLists.Response(List.of(), "next", false, null));

        mvc.perform(get("/api/articles/1/comments/3/replies").param("cursor", "previous")
                        .param("submissionId", "obsolete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCursor").value("next"));
        verify(lists).page(key, 10, "previous", null);
    }
}
