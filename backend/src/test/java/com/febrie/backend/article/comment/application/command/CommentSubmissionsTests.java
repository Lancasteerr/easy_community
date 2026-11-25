package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.query.CommentLists;
import com.febrie.backend.article.comment.application.query.CommentViews;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.web.CommentController;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

class CommentSubmissionsTests {
    private final CommentCommandResults results = mock(CommentCommandResults.class);
    private final UserActor actor = new UserActor(7, Role.USER, 1);
    private final CommentStore store = mock(CommentStore.class);
    private final CommentReplySubmissions replySubmissions = mock(CommentReplySubmissions.class);
    private final CommentViews views = mock(CommentViews.class);
    private final CommentSubmissions submissions = new CommentSubmissions(mock(CommentCommands.class), replySubmissions,
            results, mock(CommentAccess.class), store, views);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replySubmissionReusesTheStoredResultAndRejectsDifferentContent() {
        var writes = mock(CommentWrites.class);
        var replies = new CommentReplySubmissions(writes, results);
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentCommand.digest("reply:2:回复");
        var saved = new CommentCommandResults.Result(fingerprint, 3L, null);
        var created = new CommentWrites.CreatedReply(new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false), 8);
        // 第一次写入结果后，同编号的查询返回已保存内容。
        when(results.find(1, 7, id)).thenReturn(null, saved);
        when(writes.insertReply(1, actor, 2, "回复")).thenReturn(created);

        assertEquals(new CommentReplySubmissions.Applied(saved, created), replies.apply(1, actor, id, input, fingerprint));
        assertEquals(new CommentReplySubmissions.Applied(saved, null), replies.apply(1, actor, id, input, fingerprint));
        assertThrows(ApiException.class, () -> replies.apply(1, actor, id, input, "different"));
        verify(writes).insertReply(1, actor, 2, "回复");
        verify(results).insert(1, 7, id, fingerprint, 3L, null);
    }

    @Test
    void publishesAReplyFromTheInsertedRowWithoutReadingItAgain() {
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentCommand.digest("reply:2:回复");
        var row = new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false);
        var saved = new CommentCommandResults.Result(fingerprint, 3L, null);
        when(replySubmissions.apply(1, actor, id, input, fingerprint)).thenReturn(
                new CommentReplySubmissions.Applied(saved, new CommentWrites.CreatedReply(row, 8)));
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
    void statusReturnsOnlyTheCurrentActorsVisiblePublishedComment() {
        String id = UUID.randomUUID().toString();
        when(results.find(1, 7, id)).thenReturn(new CommentCommandResults.Result("stored-digest", 2L, null));
        when(store.counts(List.of(1L))).thenReturn(Map.of(1L, 14L));
        var root = new CommentRow(2, 1, 0, null, 7, "一级评论", Instant.EPOCH, false);
        var item = new CommentViews.Item("2", null, null, new CommentViews.Author("7", "作者", null),
                "一级评论", Instant.EPOCH, "ACTIVE", 0L, false, null);
        // 首次确认仍可见，后来被删除或隐藏时，历史发布状态依然有效。
        when(store.findVisiblePublished(1, 7, 2)).thenReturn(root).thenReturn(null);
        when(views.published(root, 7)).thenReturn(item);

        var published = mapper.valueToTree(submissions.status(1, actor, id));
        assertEquals("PUBLISHED", published.path("state").asString());
        assertEquals(14, published.path("commentCount").asInt());
        assertEquals("一级评论", published.at("/comment/content").asString());
        var hidden = mapper.valueToTree(submissions.status(1, actor, id));
        assertEquals("PUBLISHED", hidden.path("state").asString());
        assertFalse(hidden.has("comment"));
        var otherActor = new UserActor(8, Role.USER, 1);
        var pending = mapper.valueToTree(submissions.status(1, otherActor, id));
        assertEquals("PUBLISHING", pending.path("state").asString());
        assertFalse(pending.has("commentCount"));
        assertFalse(pending.has("comment"));
        verify(store, never()).findVisiblePublished(1, 8, 2);
    }

    @Test
    void duplicateReplyReturnsTheWinningCommentAndFailedStatusHasNoDto() {
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentCommand.digest("reply:2:回复");
        when(replySubmissions.apply(1, actor, id, input, fingerprint)).thenThrow(new DuplicateKeyException("并发提交"));
        when(results.find(1, 7, id)).thenReturn(new CommentCommandResults.Result(fingerprint, 3L, null),
                new CommentCommandResults.Result(fingerprint, null, "COMMENT_NOT_FOUND"));
        var row = new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false);
        var item = new CommentViews.Item("3", "2", "目标作者", new CommentViews.Author("7", "回复作者", null),
                "回复", Instant.EPOCH, "ACTIVE", null, null, null);
        when(store.findVisiblePublished(1, 7, 3)).thenReturn(row);
        when(views.published(row, 7)).thenReturn(item);

        assertEquals(item, submissions.submit(1, actor, "回复作者", id, input).comment());
        var failed = submissions.status(1, actor, id);
        assertEquals(CommentSubmissions.State.FAILED, failed.state());
        assertNull(failed.comment());
    }

    @Test
    void rootListCountKeepsTheExistingListFieldsAtTheTopLevel() {
        var page = new CommentLists.Response(List.of(), "next", true, null);
        var mapper = new ObjectMapper();
        var first = mapper.valueToTree(new CommentController.RootPage(page, 14L));
        assertEquals(14, first.path("commentCount").asInt());
        assertEquals("next", first.path("nextCursor").asString());
        assertEquals(0, first.path("items").size());
        assertFalse(mapper.valueToTree(new CommentController.RootPage(page, null)).has("commentCount"));
    }
}
