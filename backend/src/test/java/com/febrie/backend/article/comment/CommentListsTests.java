package com.febrie.backend.article.comment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.febrie.backend.shared.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 固定时钟复现跨版本翻页与删除过滤的边界。 */
class CommentListsTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final CommentAccess access = mock(CommentAccess.class);
    private final CommentSnapshots snapshots = mock(CommentSnapshots.class);
    private final CommentSnapshotCache cache = mock(CommentSnapshotCache.class);
    private final CommentRefreshes refreshes = mock(CommentRefreshes.class);
    private final CommentValues values = mock(CommentValues.class);
    private final CommentViews views = mock(CommentViews.class);
    private final CommentCursorCodec cursors = new CommentCursorCodec(new JsonMapper(), clock, "");
    private final CommentLists lists = new CommentLists(access, snapshots, cache, refreshes, values, cursors, views, clock);

    @Test
    void staleFirstPageAndContinuationUseOriginalHotVersion() {
        var key = new CommentListKey(1, 0, CommentListKey.Sort.HOT);
        var old = snapshot("1", 2);
        var next = snapshot("2", 3);
        when(snapshots.current(key)).thenReturn(old, next);
        when(snapshots.stale(old)).thenReturn(true);
        when(cache.snapshot(key, old.id())).thenReturn(old);
        when(cache.page(key, old, "0", 2)).thenReturn(List.of(member(51), member(52)));
        when(cache.page(key, old, "1", 2)).thenReturn(List.of(member(52)));
        var first = row(51, 0, false);
        var second = row(52, 0, false);
        when(values.objects(List.of(51L, 52L))).thenReturn(Map.of(51L, first, 52L, second));
        when(values.objects(List.of(52L))).thenReturn(Map.of(52L, second));

        var page = lists.page(key, 1, null, null);
        assertEquals("1", page.rankVersion());
        assertTrue(page.hasMore());
        verify(refreshes).offer(key, old.id());
        verify(views).items(List.of(first), null);
        assertEquals(clock.millis() + 120_000, cursors.decode(key, page.nextCursor()).expiresAt());

        var continuation = lists.page(key, 1, page.nextCursor(), null);
        assertEquals("1", continuation.rankVersion());
        assertFalse(continuation.hasMore());
        verify(views).items(List.of(second), null);
        verify(snapshots).current(key);

        when(cache.snapshot(key, old.id())).thenReturn(null);
        assertEquals("COMMENT_CURSOR_EXPIRED",
                assertThrows(ApiException.class, () -> lists.page(key, 1, page.nextCursor(), null)).code());
        assertEquals("COMMENT_INVALID", assertThrows(ApiException.class, () -> cursors.decode(
                new CommentListKey(2, 0, CommentListKey.Sort.HOT), page.nextCursor())).code());
    }

    @Test
    void replyFilteringAdvancesWithoutConsumingTheNextVisibleRow() {
        var key = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        var snapshot = snapshot("0", 3);
        when(snapshots.current(key)).thenReturn(snapshot);
        when(cache.page(key, snapshot, "", 2)).thenReturn(List.of(member(10), member(11)));
        when(cache.page(key, snapshot, member(11), 2)).thenReturn(List.of(member(12)));
        var first = row(11, 3, false);
        var second = row(12, 3, false);
        when(values.objects(List.of(10L, 11L))).thenReturn(Map.of(10L, row(10, 3, true), 11L, first));
        when(values.objects(List.of(12L))).thenReturn(Map.of(12L, second));

        var page = lists.page(key, 1, null, null);
        assertTrue(page.hasMore());
        assertEquals(member(11), cursors.decode(key, page.nextCursor()).position());
        verify(views).items(List.of(first), null);
        var tail = lists.page(key, 1, page.nextCursor(), null);
        assertFalse(tail.hasMore());
        assertEquals(member(12), cursors.decode(key, tail.nextCursor()).position());
        verify(views).items(List.of(second), null);
        verify(access, times(2)).root(1, 3, true);
    }

    private CommentSnapshot snapshot(String version, long count) {
        return new CommentSnapshot(UUID.randomUUID().toString(), version, clock.millis(), clock.millis() + 300_000, count);
    }

    private static String member(long id) { return CommentSnapshot.member(42, id); }

    private CommentRow row(long id, long root, boolean deleted) {
        return new CommentRow(id, 1, root, null, 2, deleted ? null : "正文", clock.instant(), deleted);
    }
}
