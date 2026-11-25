package com.febrie.backend.article.comment.application.query;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore.ReplyTarget;
import com.febrie.backend.article.comment.infrastructure.redis.CommentValues;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserBasicQueryService;
import com.febrie.backend.user.application.dto.UserBasicData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class CommentViewsTests {
    private final UserBasicQueryService authors = mock(UserBasicQueryService.class);
    private final MediaUrls mediaUrls = mock(MediaUrls.class);
    private final CommentValues values = mock(CommentValues.class);
    private final CommentViews views = new CommentViews(authors, mock(LikeQueryService.class), mediaUrls, values);
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void onlyRootListAuthorsWithAnAvatarExposeItsPublicUrl() {
        var rootWithAvatar = new CommentRow(11, 1, 0, null, 7, "一级评论", Instant.EPOCH, false);
        var rootWithoutAvatar = new CommentRow(12, 1, 0, null, 8, "另一条评论", Instant.EPOCH, false);
        // 一级评论和二级回复共用同一作者，验证头像字段按评论层级输出。
        var reply = new CommentRow(13, 1, 11, 11L, 7, "二级回复", Instant.EPOCH, false);
        when(authors.get(List.of(7L, 8L))).thenReturn(Map.of(
                7L, new UserBasicData(7, "有头像", "avatar-id", null, Instant.EPOCH),
                8L, new UserBasicData(8, "无头像", null, null, Instant.EPOCH)));
        when(mediaUrls.publicUrl("avatar-id")).thenReturn("https://media.test/avatar");

        var payload = mapper.valueToTree(views.items(List.of(rootWithAvatar, rootWithoutAvatar, reply), null));

        assertEquals("https://media.test/avatar", payload.at("/0/author/avatarUrl").asString());
        assertFalse(payload.get(0).has("replyToUsername"));
        assertFalse(payload.get(0).has("replyToId"));
        assertFalse(payload.at("/1/author").has("avatarUrl"));
        assertFalse(payload.at("/2/author").has("avatarUrl"));
        verify(authors).get(List.of(7L, 8L));
        verify(mediaUrls).publicUrl("avatar-id");
    }

    @Test
    void replyPageUsesActualTargetAuthorsIncludingDeletedTargets() {
        var replies = List.of(reply(31, 11), reply(32, 21), reply(33, 21), reply(34, 22), reply(35, 23), reply(36, 99));
        // 楼主不进入目标查询；软删除目标仍提供归属与作者。
        when(values.replyTargets(List.of(21L, 22L, 23L, 99L))).thenReturn(Map.of(
                21L, new ReplyTarget(1, 11, 9),
                22L, new ReplyTarget(1, 22, 10), 23L, new ReplyTarget(2, 11, 10)));
        when(authors.get(List.of(7L, 9L))).thenReturn(Map.of(
                7L, profile(7, "回复作者"), 9L, profile(9, "已删除目标作者")));

        var page = new CommentLists.Response(views.items(replies, null), "next", true, null);
        var payload = mapper.valueToTree(page);

        assertFalse(payload.at("/items/0").has("replyToUsername"));
        assertFalse(payload.at("/items/0").has("replyToId"));
        assertEquals("已删除目标作者", payload.at("/items/1/replyToUsername").asString());
        assertEquals("已删除目标作者", payload.at("/items/2/replyToUsername").asString());
        assertEquals("11", payload.at("/items/1/rootId").asString());
        assertFalse(payload.at("/items/1").has("replyToId"));
        assertFalse(payload.at("/items/3").has("replyToUsername"));
        assertFalse(payload.at("/items/4").has("replyToUsername"));
        assertFalse(payload.at("/items/5").has("replyToUsername"));
        verify(values).replyTargets(List.of(21L, 22L, 23L, 99L));
        verify(authors).get(List.of(7L, 9L));
    }

    @Test
    void directReplyToRootOmitsTheTargetInCreationAndConfirmation() {
        var row = reply(31, 11);
        var created = mapper.valueToTree(views.createdReply(row, "回复作者", 8));
        verifyNoInteractions(authors, values);

        // 确认时只装配评论作者，不查楼主资料。
        when(authors.get(List.of(7L))).thenReturn(Map.of(7L, profile(7, "回复作者")));
        var published = mapper.valueToTree(views.published(row, 7));
        for (var payload : List.of(created, published)) {
            assertFalse(payload.has("replyToUsername"));
            assertFalse(payload.has("replyToId"));
            assertEquals("11", payload.path("rootId").asString());
            assertEquals("回复作者", payload.at("/author/username").asString());
        }
        verify(authors).get(List.of(7L));
        verifyNoMoreInteractions(authors);
        verifyNoInteractions(values);
    }

    @Test
    void newlyCreatedReplyUsesTheTargetAuthorFromTheWrite() {
        when(authors.get(8L)).thenReturn(Optional.of(profile(8, "目标作者")));
        when(authors.get(9L)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TEST", "资料暂不可用"));

        var payload = mapper.valueToTree(views.createdReply(reply(31, 21), "回复作者", 8));
        var unavailable = mapper.valueToTree(views.createdReply(reply(32, 22), "回复作者", 9));

        assertEquals("回复作者", payload.at("/author/username").asString());
        assertEquals("目标作者", payload.path("replyToUsername").asString());
        assertFalse(payload.has("replyToId"));
        assertFalse(payload.path("author").has("avatarUrl"));
        assertFalse(unavailable.has("replyToUsername"));
        assertEquals("正文", unavailable.path("content").asString());
        verifyNoInteractions(values);
    }

    private static UserBasicData profile(long id, String username) {
        return new UserBasicData(id, username, null, "", Instant.EPOCH);
    }

    private static CommentRow reply(long id, long targetId) {
        return new CommentRow(id, 1, 11, targetId, 7, "正文", Instant.EPOCH, false);
    }
}
