package com.febrie.backend.article.content.application;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 故障分类不重放SQL；真实回滚另由MySQL测试验证。 */
class ArticleServiceTests {
    private final ArticleContentRenderer renderer = mock(ArticleContentRenderer.class);
    private final ArticleCommitService commits = mock(ArticleCommitService.class);
    private final ArticleCacheMaintenance cache = mock(ArticleCacheMaintenance.class);
    private final com.febrie.backend.article.tag.application.TagCatalog tags = mock(com.febrie.backend.article.tag.application.TagCatalog.class);
    private final ArticleService service = new ArticleService(renderer, commits, cache, tags);
    private final UserActor actor = new UserActor(1, Role.USER, 1);
    @Test void invalidTagsNeverEnterArticleTransaction() {
        var command = new com.febrie.backend.article.content.application.dto.SaveArticleCommand(null, "x", null, null, List.of(9L));
        var prepared = new com.febrie.backend.article.content.application.dto.PreparedArticle("x", "x", "{}", "x", null, Set.of(), List.of(9L));
        when(renderer.prepare("x", null, null, List.of(9L))).thenReturn(prepared);
        doThrow(com.febrie.backend.article.common.ArticleErrors.invalid()).when(tags).requireAllExist(List.of(9L));
        assertEquals("INVALID_ARTICLE", assertThrows(ApiException.class, () -> service.save(actor, command)).code());
        verifyNoInteractions(commits, cache);
    }
    @Test void distinguishesBodyFailureFromUnconfirmedCommit() {
        doAnswer(call -> { throw new CannotAcquireLockException("deadlock"); }).when(commits).hide(eq(actor), eq(1L), any());
        assertEquals("DEPENDENCY_UNAVAILABLE", assertThrows(ApiException.class, () -> service.hide(actor, 1)).code());
        verify(commits, times(1)).hide(eq(actor), eq(1L), any());
        doAnswer(call -> {
            ArticleCommitService.Attempt attempt = call.getArgument(2); attempt.bodyCompleted = true;
            throw new TransactionSystemException("COMMIT回执丢失");
        }).when(commits).hide(eq(actor), eq(2L), any());
        assertEquals("ARTICLE_WRITE_RESULT_UNKNOWN", assertThrows(ApiException.class, () -> service.hide(actor, 2)).code());
        verify(commits, times(1)).hide(eq(actor), eq(2L), any());
        verifyNoInteractions(cache);
    }
    @Test void preservesKnownCommitEvenWhenCleanupFails() {
        doAnswer(call -> {
            ArticleCommitService.Attempt attempt = call.getArgument(2);
            attempt.committed = true; attempt.id = 1;
            throw new TransactionSystemException("提交后清理失败");
        }).when(commits).hide(eq(actor), eq(1L), any());
        assertDoesNotThrow(() -> service.hide(actor, 1));
        verify(commits, times(1)).hide(eq(actor), eq(1L), any());
        verify(cache).afterCommit(1, true);
    }
    @Test void unexpectedCacheExceptionDoesNotOverrideSqlSuccess() {
        doThrow(new IllegalStateException("意外缓存异常")).when(cache).afterCommit(1, true);
        assertDoesNotThrow(() -> service.hide(actor, 1));
        verify(commits, times(1)).hide(eq(actor), eq(1L), any());
    }
}
