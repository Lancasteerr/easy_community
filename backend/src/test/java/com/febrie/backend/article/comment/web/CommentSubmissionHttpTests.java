package com.febrie.backend.article.comment.web;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.application.command.CommentInput;
import com.febrie.backend.article.comment.application.command.CommentSubmissions;
import com.febrie.backend.article.comment.application.command.CommentWriteService;
import com.febrie.backend.article.comment.application.query.CommentCountQuery;
import com.febrie.backend.article.comment.application.query.CommentLists;
import com.febrie.backend.article.comment.application.query.CommentRootLists;
import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.article.like.application.LikeWriteService;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.shared.web.ApiExceptionHandler;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 直接验证同步接口和 HTTP 状态，无需启动服务器或消息依赖。 */
class CommentSubmissionHttpTests {
    @Test
    void synchronousPutReplacesStatusEndpointAndDeleteReturnsNoContent() throws Exception {
        var submissions = mock(CommentSubmissions.class);
        var writes = mock(CommentWriteService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CommentController(mock(CommentLists.class), writes,
                        mock(CommentAccess.class), mock(LikeWriteService.class), mock(CommentRootLists.class),
                        mock(CommentCountQuery.class), submissions))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        String route = "/api/articles/1/comments";
        String id = "93b11dd1-ee65-4ec1-b34b-c84d247cca81";
        String path = route + "/submissions/" + id;
        var actor = new UserActor(7, Role.USER, 1);
        var item = new CommentViews.Item("8", null, null, new CommentViews.Author("7", "作者", null),
                "正文", Instant.EPOCH, "ACTIVE", 0L, false, null);
        when(submissions.submit(1, actor, "作者", id, new CommentInput("正文", null)))
                .thenReturn(new CommentSubmissions.View(CommentSubmissions.State.PUBLISHED, item));
        when(submissions.submit(1, actor, "作者", id, new CommentInput("其他内容", null)))
                .thenThrow(CommentErrors.operationConflict());

        var principal = new SessionPrincipal(7, "作者", Role.USER, 1, Instant.EPOCH, Instant.MAX);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null));
        try {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"正文\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PUBLISHED"))
                    .andExpect(jsonPath("$.comment.id").value("8")).andExpect(jsonPath("$.commentCount").doesNotExist());
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"其他内容\"}"))
                    .andExpect(status().isConflict());
            mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
            mvc.perform(delete(route + "/8").header("X-Operation-Id", id)).andExpect(status().isNoContent());
            verify(writes).delete(1, 8, actor, id);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
