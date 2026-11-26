package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.query.CommentLists;
import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults.Result;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.web.CommentController;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommentSubmissionsTests {
    private final CommentCommandResults results = mock(CommentCommandResults.class);
    private final UserActor actor = new UserActor(7, Role.USER, 1);
    private final CommentStore store = mock(CommentStore.class);
    private final CommentSubmissionTransactions transactions = mock(CommentSubmissionTransactions.class);
    private final CommentViews views = mock(CommentViews.class);
    private final CommentSubmissions submissions = new CommentSubmissions(transactions, results, store, views);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replySubmissionReusesTheStoredResultAndRejectsDifferentContentOrTarget() {
        var writes = mock(CommentWrites.class);
        var writesWithResult = new CommentSubmissionTransactions(writes, results);
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentOperation.submission(input);
        var saved = new Result(fingerprint, 3L, null);
        var row = new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false);
        when(results.find(1, 7, id)).thenReturn(null, saved);
        when(writes.insertReply(1, actor, 2, "回复")).thenReturn(new CommentWrites.CreatedReply(row, 8));

        var first = writesWithResult.apply(1, actor, id, input, fingerprint);
        assertEquals(new CommentSubmissionTransactions.Created(row, 8L), first.created());
        assertEquals(new CommentSubmissionTransactions.Applied(saved, null),
                writesWithResult.apply(1, actor, id, input, fingerprint));
        assertConflict(writesWithResult, id, new CommentInput("换内容", 2L));
        assertConflict(writesWithResult, id, new CommentInput("回复", 4L));
        verify(writes).insertReply(1, actor, 2, "回复");
        verify(results).insert(1, 7, id, fingerprint, 3L, null);
    }

    @Test
    void publishesAReplyFromTheInsertedRowWithoutReadingItAgain() {
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentOperation.submission(input);
        var row = new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false);
        var saved = new Result(fingerprint, 3L, null);
        when(transactions.apply(1, actor, id, input, fingerprint)).thenReturn(
                new CommentSubmissionTransactions.Applied(saved, new CommentSubmissionTransactions.Created(row, 8L)));
        var item = new CommentViews.Item("3", "2", "目标作者", new CommentViews.Author("7", "回复作者", null),
                "回复", Instant.EPOCH, "ACTIVE", null, null, null);
        when(views.createdReply(row, "回复作者", 8)).thenReturn(item);

        var published = mapper.valueToTree(submissions.submit(1, actor, "回复作者", id, input));
        assertEquals("PUBLISHED", published.path("state").asString());
        assertEquals("目标作者", published.at("/comment/replyToUsername").asString());
        assertFalse(published.path("comment").has("replyToId"));
        assertFalse(published.has("commentCount"));
        verifyNoInteractions(store);
    }

    @Test
    void retryReturnsTheOriginalCommentAndRespectsCurrentVisibility() {
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("一级评论", null);
        String fingerprint = CommentOperation.submission(input);
        var root = new CommentRow(2, 1, 0, null, 7, "一级评论", Instant.EPOCH, false);
        var item = new CommentViews.Item("2", null, null, new CommentViews.Author("7", "作者", null),
                "一级评论", Instant.EPOCH, "ACTIVE", 0L, false, null);
        when(transactions.apply(1, actor, id, input, fingerprint)).thenReturn(
                new CommentSubmissionTransactions.Applied(new Result(fingerprint, 2L, null), null));
        when(store.findVisiblePublished(1, 7, 2)).thenReturn(root).thenReturn(null);
        when(views.published(root, 7)).thenReturn(item);

        var published = mapper.valueToTree(submissions.submit(1, actor, "作者", id, input));
        assertEquals("PUBLISHED", published.path("state").asString());
        assertEquals("2", published.at("/comment/id").asString());
        assertFalse(published.has("commentCount"));
        // 评论被隐藏后，历史发布成功仍有效，但省略已不可见的正文。
        var hidden = mapper.valueToTree(submissions.submit(1, actor, "作者", id, input));
        assertEquals("PUBLISHED", hidden.path("state").asString());
        assertFalse(hidden.has("comment"));
    }

    @Test
    void duplicateReplyUsesWinningTransactionAndFailedResultHasNoDto() {
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentOperation.submission(input);
        when(transactions.apply(1, actor, id, input, fingerprint)).thenThrow(new DuplicateKeyException("并发提交"));
        when(results.find(1, 7, id)).thenReturn(new Result(fingerprint, 3L, null),
                new Result(fingerprint, null, "COMMENT_NOT_FOUND"));
        var row = new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false);
        var item = new CommentViews.Item("3", "2", "目标作者", new CommentViews.Author("7", "回复作者", null),
                "回复", Instant.EPOCH, "ACTIVE", null, null, null);
        when(store.findVisiblePublished(1, 7, 3)).thenReturn(row);
        when(views.published(row, 7)).thenReturn(item);

        assertEquals(item, submissions.submit(1, actor, "回复作者", id, input).comment());
        var failed = submissions.submit(1, actor, "回复作者", id, input);
        assertEquals(CommentSubmissions.State.FAILED, failed.state());
        assertNull(failed.comment());
    }

    @Test
    void rootListCountKeepsTheExistingListFieldsAtTheTopLevel() {
        var page = new CommentLists.Response(List.of(), "next", true, null);
        var first = mapper.valueToTree(new CommentController.RootPage(page, 14L));
        assertEquals(14, first.path("commentCount").asInt());
        assertEquals("next", first.path("nextCursor").asString());
        assertEquals(0, first.path("items").size());
        assertFalse(mapper.valueToTree(new CommentController.RootPage(page, null)).has("commentCount"));
    }

    private void assertConflict(CommentSubmissionTransactions service, String id, CommentInput changed) {
        var error = assertThrows(ApiException.class,
                () -> service.apply(1, actor, id, changed, CommentOperation.submission(changed)));
        assertEquals(409, error.status().value());
    }
}
