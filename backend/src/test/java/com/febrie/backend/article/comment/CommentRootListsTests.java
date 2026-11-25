package com.febrie.backend.article.comment;

import com.febrie.backend.article.comment.infrastructure.CommentCursorCodec;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.redis.CommentSnapshot;
import com.febrie.backend.article.comment.infrastructure.redis.CommentValues;
import com.febrie.backend.article.comment.infrastructure.search.CommentIndex;
import com.febrie.backend.article.search.application.SearchCursor.Value;
import com.febrie.backend.shared.error.ApiException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** PIT 切换、删除过滤和故障映射通过 ES 客户端替身验证。 */
class CommentRootListsTests {
    private final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    private final CommentIndex index = mock(CommentIndex.class);
    private final CommentValues values = mock(CommentValues.class);
    private final CommentLists replies = mock(CommentLists.class);
    private final CommentViews views = mock(CommentViews.class);
    private final CommentCursorCodec tokens = new CommentCursorCodec(new JsonMapper(), clock, "");
    private final CommentRootLists service = new CommentRootLists(mock(CommentAccess.class), index, values, replies,
            views, tokens, clock);
    private final CommentListKey hot = new CommentListKey(1, 0, CommentListKey.Sort.HOT);

    @Test
    void continuationUsesLatestPitAndSkipsNowInvisibleCandidates() throws Exception {
        var first = hit(8);
        var second = hit(9);
        var third = hit(10);
        var a = row(8);
        var b = row(10);
        when(index.open()).thenReturn("initial");
        when(index.page(hot, "initial", List.of(), 2)).thenReturn(new CommentIndex.Batch("next", List.of(first, second)));
        when(index.page(hot, "next", first.sort(), 2)).thenReturn(new CommentIndex.Batch("last", List.of(second, third)));
        when(index.page(hot, "last", third.sort(), 2)).thenReturn(new CommentIndex.Batch("closed", List.of()));
        when(values.objects(List.of(8L, 9L))).thenReturn(Map.of(8L, a, 9L, row(9)));
        when(values.objects(List.of(9L, 10L))).thenReturn(Map.of(10L, b));

        var page = service.page(hot, 1, null, null);
        assertTrue(page.hasMore());
        var tail = service.page(hot, 1, page.nextCursor(), null);
        assertEquals(page.rankVersion(), tail.rankVersion());
        assertFalse(tail.hasMore());
        verify(views).items(List.of(a), null);
        verify(views).items(List.of(b), null);
        verify(index).close("closed");
        var otherArticle = new CommentListKey(2, 0, CommentListKey.Sort.HOT);
        assertEquals("COMMENT_INVALID", assertThrows(ApiException.class,
                () -> service.page(otherArticle, 1, page.nextCursor(), null)).code());
    }

    @Test
    void searchFailureReturnsUnavailableAndClosesNewPit() throws Exception {
        when(index.open()).thenReturn("pit");
        when(index.page(hot, "pit", List.of(), 2)).thenThrow(new IOException("测试故障"));
        assertEquals("COMMENT_UNAVAILABLE",
                assertThrows(ApiException.class, () -> service.page(hot, 1, null, null)).code());
        verify(index).close("pit");
    }

    @Test
    void oldRootTokensExpireAndOldReplyTokensKeepTheirPosition() {
        String oldRoot = tokens.seal(Map.of("key", hot, "snapshotId", "legacy", "rankVersion", "1",
                "position", "1", "expiresAt", 1000));
        assertEquals("COMMENT_CURSOR_EXPIRED",
                assertThrows(ApiException.class, () -> service.page(hot, 20, oldRoot, null)).code());
        var replyKey = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        String position = CommentSnapshot.member(200, 19);
        String oldReply = tokens.seal(Map.of("key", replyKey, "position", position,
                "snapshotId", "", "rankVersion", "", "expiresAt", 1000));
        assertEquals(position, tokens.decode(replyKey, oldReply).position());
        verifyNoInteractions(index);
    }

    private static CommentIndex.Hit hit(long id) {
        return new CommentIndex.Hit(id, List.of(new Value("long", "0"), new Value("long", Long.toString(id)),
                new Value("long", Long.toString(id))));
    }

    private CommentRow row(long id) {
        return new CommentRow(id, 1, 0, null, 2, "正文", clock.instant(), false);
    }
}
