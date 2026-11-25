package com.febrie.backend.article.comment.application.query;

import com.febrie.backend.article.comment.application.CommentAccess;
import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.CommentCursorCodec;
import com.febrie.backend.article.comment.infrastructure.messaging.CommentRefreshes;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore;
import com.febrie.backend.article.comment.infrastructure.redis.CommentSnapshot;
import com.febrie.backend.article.comment.infrastructure.redis.CommentSnapshotCache;
import com.febrie.backend.article.comment.infrastructure.redis.CommentValues;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import com.febrie.backend.shared.error.ApiException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/** 每次请求读取一个索引快照，再把候选批量装配为公开评论。 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommentLists {
    private static final int MAX_BATCHES = 8;
    private final CommentAccess access;
    private final CommentSnapshots snapshots;
    private final CommentSnapshotCache cache;
    private final CommentRefreshes refreshes;
    private final CommentValues values;
    private final CommentCursorCodec cursors;
    private final CommentViews views;
    private final Clock clock;
    private final CommentStore store;

    public CommentLists(CommentAccess access, CommentSnapshots snapshots, CommentSnapshotCache cache,
                        CommentRefreshes refreshes, CommentValues values, CommentCursorCodec cursors,
                        CommentViews views, Clock clock, CommentStore store) {
        this.access = access;
        this.snapshots = snapshots;
        this.cache = cache;
        this.refreshes = refreshes;
        this.values = values;
        this.cursors = cursors;
        this.views = views;
        this.clock = clock;
        this.store = store;
    }

    public Response page(CommentListKey key, int size, String encoded, Long user) {
        try {
            return readPage(key, size, encoded, user);
        } catch (DataAccessException failure) {
            throw CommentErrors.unavailable();
        } catch (ApiException failure) {
            if (failure.status().is5xxServerError()) throw CommentErrors.unavailable();
            throw failure;
        }
    }

    /** 已确认发布后的刷新读一段主库分页，避免仍有效的共享快照遮住刚提交的回复。 */
    public Response publishedReplyPage(CommentListKey key, int size, String encoded, long user) {
        try {
            access.article(key.articleId());
            access.root(key.articleId(), key.rootId());
            var cursor = encoded == null ? null : cursors.decode(key, encoded);
            String start = cursor == null ? "" : cursor.position();
            var candidates = store.repliesAfter(key, start, size + 1);
            var rows = candidates.subList(0, Math.min(size, candidates.size()));
            String position = rows.isEmpty() ? start : member(rows.getLast());
            long deadline = cursor == null ? clock.millis() + 600_000 : cursor.expiresAt();
            if (deadline <= clock.millis()) throw CommentErrors.expired();
            String next = cursors.encode(new CommentCursorCodec.Cursor(key, position, deadline));
            return new Response(views.items(rows, user), next, candidates.size() > size, null);
        } catch (DataAccessException failure) {
            throw CommentErrors.unavailable();
        } catch (ApiException failure) {
            if (failure.status().is5xxServerError()) throw CommentErrors.unavailable();
            throw failure;
        }
    }

    private Response readPage(CommentListKey key, int size, String encoded, Long user) {
        access.article(key.articleId());
        access.root(key.articleId(), key.rootId());
        var cursor = encoded == null ? null : cursors.decode(key, encoded);
        for (int attempt = 0; attempt < 2; attempt++) {
            var snapshot = snapshots.current(key);
            String start = cursor == null ? "" : cursor.position();
            // 刷新可以异步切换指针，本次读取继续使用已选快照。
            if (snapshots.stale(snapshot)) refreshes.offer(key, snapshot.id());
            var page = scan(key, snapshot, start, size);
            if (page == null) continue;
            long deadline = cursor == null ? clock.millis() + 600_000 : cursor.expiresAt();
            if (deadline <= clock.millis()) throw CommentErrors.expired();
            var next = cursors.encode(new CommentCursorCodec.Cursor(key, page.position(), deadline));
            return new Response(views.items(page.rows(), user), next, page.more(), null);
        }
        throw CommentErrors.unavailable();
    }

    private Page scan(CommentListKey key, CommentSnapshot snapshot, String start, int size) {
        var found = new ArrayList<CommentRow>(size);
        String position = start;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var members = cache.page(key, snapshot, position, size + 1);
            if (members == null) return null;
            if (members.isEmpty()) return new Page(found, position, false);
            var rows = values.objects(members.stream().map(CommentSnapshot::commentId).toList());
            for (String member : members) {
                var row = rows.get(CommentSnapshot.commentId(member));
                if (row != null && row.articleId() == key.articleId() && row.rootId() == key.rootId()
                        && !row.deleted()) {
                    // 多读一条只用于判定下一页，位置停在该条之前。
                    if (found.size() == size) return new Page(found, position, true);
                    found.add(row);
                }
                position = member;
            }
            if (members.size() < size + 1) return new Page(found, position, false);
        }
        // 尚有未验证的候选，不能把它们误报为可见的下一页。
        throw CommentErrors.unavailable();
    }

    /** 预览先批量读取热楼，只在确实缺键时合并读取冷楼的前三条。 */
    Map<Long, Response> previews(List<CommentRow> roots, Long user) {
        if (roots.isEmpty()) return Map.of();
        long article = roots.getFirst().articleId();
        var keys = roots.stream().map(root -> new CommentListKey(article, root.id(), CommentListKey.Sort.REPLIES)).toList();
        var snapshotsByKey = cache.currentAll(keys);
        var candidates = cache.firstPages(snapshotsByKey, 3);
        var objects = values.objects(candidates.values().stream().flatMap(List::stream)
                .map(CommentSnapshot::commentId).distinct().toList());
        var pages = new LinkedHashMap<CommentListKey, Page>();
        var cold = new ArrayList<Long>();
        for (var key : keys) {
            var members = candidates.get(key);
            if (members == null) {
                cold.add(key.rootId());
                continue;
            }
            var snapshot = snapshotsByKey.get(key);
            if (snapshots.stale(snapshot)) refreshes.offer(key, snapshot.id());
            var page = previewCandidates(key, members, objects);
            if (page == null) page = scan(key, snapshot, "", 2);
            if (page == null) cold.add(key.rootId());
            else pages.put(key, page);
        }
        if (!cold.isEmpty()) {
            var loaded = store.previews(article, cold);
            for (long root : cold) {
                var rows = loaded.getOrDefault(root, List.of());
                var displayed = rows.subList(0, Math.min(rows.size(), 2));
                String position = displayed.isEmpty() ? "" : member(displayed.getLast());
                pages.put(new CommentListKey(article, root, CommentListKey.Sort.REPLIES),
                        new Page(displayed, position, rows.size() > 2));
            }
        }
        var viewsById = views.items(pages.values().stream().flatMap(page -> page.rows().stream()).toList(), user)
                .stream().collect(Collectors.toMap(CommentViews.Item::id, Function.identity()));
        long deadline = clock.millis() + 600_000;
        var result = new HashMap<Long, Response>();
        pages.forEach((key, page) -> result.put(key.rootId(), new Response(
                page.rows().stream().map(row -> viewsById.get(Long.toString(row.id()))).toList(),
                cursors.encode(new CommentCursorCodec.Cursor(key, page.position(), deadline)),
                page.more(), null)));
        return result;
    }

    private static Page previewCandidates(CommentListKey key, List<String> members, Map<Long, CommentRow> rows) {
        var found = new ArrayList<CommentRow>(2);
        String position = "";
        for (String member : members) {
            var row = rows.get(CommentSnapshot.commentId(member));
            if (row != null && !row.deleted() && row.articleId() == key.articleId() && row.rootId() == key.rootId()) {
                if (found.size() == 2) return new Page(found, position, true);
                found.add(row);
            }
            position = member;
        }
        // 三条候选里出现过滤时，需要继续寻找真正的下一条可见回复。
        return members.size() < 3 ? new Page(found, position, false) : null;
    }

    private static String member(CommentRow row) {
        return CommentSnapshot.member(row.createdAt().toEpochMilli(), row.id());
    }

    private record Page(List<CommentRow> rows, String position, boolean more) { }

    public record Response(List<CommentViews.Item> items, String nextCursor, boolean hasMore,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String rankVersion) { }
}
