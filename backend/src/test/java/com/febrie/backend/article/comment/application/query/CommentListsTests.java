package com.febrie.backend.article.comment.application.query;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.infrastructure.CommentCursorCodec;
import com.febrie.backend.article.comment.infrastructure.messaging.CommentRefreshes;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.infrastructure.redis.CommentSnapshot;
import com.febrie.backend.article.comment.infrastructure.redis.CommentSnapshotCache;
import com.febrie.backend.article.comment.infrastructure.redis.CommentValues;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 固定时钟验证回复游标和删除过滤的边界。 */
class CommentListsTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final CommentAccess access = mock(CommentAccess.class);
    private final CommentSnapshots snapshots = mock(CommentSnapshots.class);
    private final CommentSnapshotCache cache = mock(CommentSnapshotCache.class);
    private final CommentRefreshes refreshes = mock(CommentRefreshes.class);
    private final CommentValues values = mock(CommentValues.class);
    private final CommentViews views = mock(CommentViews.class);
    private final CommentCursorCodec cursors = new CommentCursorCodec(new JsonMapper(), clock, "");
    private final CommentStore store = mock(CommentStore.class);
    private final CommentLists lists = new CommentLists(access, snapshots, cache, refreshes, values, cursors, views, clock, store);

    @Test
    void replyFilteringAdvancesWithoutConsumingTheNextVisibleRow() {
        var key = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        var snapshot = snapshot(3);
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
        verify(access, times(2)).root(1, 3);
    }

    @Test
    void coldPreviewDisplaysTwoAndDoesNotConsumeThirdReply() {
        var key = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        var first = row(11, 3, false);
        var second = row(12, 3, false);
        var third = row(13, 3, false);
        when(store.previews(1, List.of(3L))).thenReturn(Map.of(3L, List.of(first, second, third)));
        when(views.items(List.of(first, second), null)).thenReturn(List.of(item(11), item(12)));

        var preview = lists.previews(List.of(row(3, 0, false)), null).get(3L);
        assertEquals(List.of("11", "12"), preview.items().stream().map(CommentViews.Item::id).toList());
        assertTrue(preview.hasMore());
        assertEquals(CommentSnapshot.member(clock.millis(), 12), cursors.decode(key, preview.nextCursor()).position());
        verifyNoInteractions(snapshots);
    }

    @Test
    void emptyTailCanContinueAfterSnapshotRefresh() {
        var key = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        var empty = snapshot(0);
        var next = snapshot(1);
        when(snapshots.current(key)).thenReturn(empty, next);
        when(cache.page(key, empty, "", 11)).thenReturn(List.of());
        when(cache.page(key, next, "", 11)).thenReturn(List.of(member(11)));
        var reply = row(11, 3, false);
        when(values.objects(List.of(11L))).thenReturn(Map.of(11L, reply));
        when(views.items(List.of(reply), null)).thenReturn(List.of(item(11)));

        var initial = lists.page(key, 10, null, null);
        assertFalse(initial.hasMore());
        assertEquals("", cursors.decode(key, initial.nextCursor()).position());
        var continued = lists.page(key, 10, initial.nextCursor(), null);
        assertFalse(continued.hasMore());
        assertEquals(List.of("11"), continued.items().stream().map(CommentViews.Item::id).toList());
    }

    @Test
    void confirmedReplyRefreshReadsOnePageFromTheExistingPosition() {
        var key = new CommentListKey(1, 3, CommentListKey.Sort.REPLIES);
        String previous = CommentSnapshot.member(clock.millis(), 10);
        var cursor = cursors.encode(new CommentCursorCodec.Cursor(key, previous, clock.millis() + 60_000));
        var reply = row(11, 3, false);
        // 第二条仅用于判断是否还有更多，刷新不会跳过这条回复。
        when(store.repliesAfter(key, previous, 2)).thenReturn(List.of(reply, row(12, 3, false)));
        when(views.items(List.of(reply), 2L)).thenReturn(List.of(item(11)));

        var page = lists.publishedReplyPage(key, 1, cursor, 2);
        assertEquals(List.of("11"), page.items().stream().map(CommentViews.Item::id).toList());
        assertTrue(page.hasMore());
        assertEquals(CommentSnapshot.member(clock.millis(), 11), cursors.decode(key, page.nextCursor()).position());
        verifyNoInteractions(snapshots);
    }

    private CommentViews.Item item(long id) {
        return new CommentViews.Item(Long.toString(id), "3", "3", null, "正文", clock.instant(), "ACTIVE", null, null, null);
    }

    private CommentSnapshot snapshot(long count) {
        return new CommentSnapshot(UUID.randomUUID().toString(), clock.millis(), clock.millis() + 300_000, count);
    }

    private static String member(long id) { return CommentSnapshot.member(42, id); }

    private CommentRow row(long id, long root, boolean deleted) {
        return new CommentRow(id, 1, root, null, 2, deleted ? null : "正文", clock.instant(), deleted);
    }
}
