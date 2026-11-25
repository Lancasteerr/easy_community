package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.query.CommentLists;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentCommandResults;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.web.CommentController;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CommentSubmissionsTests {
    private final CommentCommandResults results = mock(CommentCommandResults.class);
    private final UserActor actor = new UserActor(7, Role.USER, 1);

    @Test
    void replySubmissionReusesTheStoredResultAndRejectsDifferentContent() {
        var writes = mock(CommentWrites.class);
        var replies = new CommentReplySubmissions(writes, results);
        String id = UUID.randomUUID().toString();
        var input = new CommentInput("回复", 2L);
        String fingerprint = CommentCommand.digest("reply:2:回复");
        var saved = new CommentCommandResults.Result(fingerprint, 3L, null);
        // 第一次写入结果后，同编号的查询返回已保存内容。
        when(results.find(1, 7, id)).thenReturn(null, saved);
        when(writes.insertReply(1, actor, 2, "回复"))
                .thenReturn(new CommentRow(3, 1, 2, 2L, 7, "回复", Instant.EPOCH, false));

        assertEquals(saved, replies.apply(1, actor, id, input, fingerprint));
        assertEquals(saved, replies.apply(1, actor, id, input, fingerprint));
        assertThrows(ApiException.class, () -> replies.apply(1, actor, id, input, "different"));
        verify(writes).insertReply(1, actor, 2, "回复");
        verify(results).insert(1, 7, id, fingerprint, 3L, null);
    }

    @Test
    void statusUsesTheCurrentActorAndOnlyExposesPublicationAndCount() {
        var store = mock(CommentStore.class);
        var submissions = new CommentSubmissions(mock(CommentCommands.class), mock(CommentReplySubmissions.class),
                results, mock(CommentAccess.class), store);
        String id = UUID.randomUUID().toString();
        when(results.find(1, 7, id)).thenReturn(new CommentCommandResults.Result("stored-digest", 2L, null));
        when(store.counts(List.of(1L))).thenReturn(Map.of(1L, 14L));

        var mapper = new ObjectMapper();
        var published = mapper.valueToTree(submissions.status(1, actor, id));
        assertEquals("PUBLISHED", published.path("state").asString());
        assertEquals(14, published.path("commentCount").asInt());
        assertEquals(2, published.size());
        var otherActor = new UserActor(8, Role.USER, 1);
        var pending = mapper.valueToTree(submissions.status(1, otherActor, id));
        assertEquals("PUBLISHING", pending.path("state").asString());
        assertFalse(pending.has("commentCount"));
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
