package com.febrie.backend.article.comment.application.query;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.user.application.UserBasicQueryService;
import com.febrie.backend.user.application.dto.UserBasicData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CommentViewsTests {
    @Test
    void onlyRootListAuthorsWithAnAvatarExposeItsPublicUrl() {
        var authors = mock(UserBasicQueryService.class);
        var mediaUrls = mock(MediaUrls.class);
        var views = new CommentViews(authors, mock(LikeQueryService.class), mediaUrls);
        var mapper = new JsonMapper();
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
        assertFalse(payload.at("/1/author").has("avatarUrl"));
        assertFalse(payload.at("/2/author").has("avatarUrl"));
        assertFalse(mapper.valueToTree(views.created(reply, "有头像")).path("author").has("avatarUrl"));
        verify(authors).get(List.of(7L, 8L));
        verify(mediaUrls).publicUrl("avatar-id");
    }
}
