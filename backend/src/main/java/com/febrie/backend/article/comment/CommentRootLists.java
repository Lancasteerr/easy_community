package com.febrie.backend.article.comment;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.febrie.backend.article.search.application.SearchCursor;
import com.febrie.backend.shared.error.ApiException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** PIT 固定一级顺序，Redis 对象缓存独立裁决每个候选的当前可见性。 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class CommentRootLists {
    private static final String PREFIX = "es1.";
    private static final int MAX_BATCHES = 8;
    private final CommentAccess access;
    private final CommentIndex index;
    private final CommentValues values;
    private final CommentLists replies;
    private final CommentViews views;
    private final CommentCursorCodec tokens;
    private final Clock clock;

    CommentRootLists(CommentAccess access, CommentIndex index, CommentValues values, CommentLists replies,
                     CommentViews views, CommentCursorCodec tokens, Clock clock) {
        this.access = access;
        this.index = index;
        this.values = values;
        this.replies = replies;
        this.views = views;
        this.tokens = tokens;
        this.clock = clock;
    }

    CommentLists.Response page(CommentListKey key, int size, String encoded, Long user) {
        if (size < 1 || size > 50) throw CommentErrors.invalid();
        var cursor = encoded == null ? null : decode(key, encoded);
        String pit = cursor == null ? null : cursor.pit();
        String rank = cursor == null
                ? (key.sort() == CommentListKey.Sort.HOT ? UUID.randomUUID().toString() : null)
                : cursor.rankVersion();
        long deadline = cursor == null ? clock.millis() + 120_000 : cursor.expiresAt();
        boolean completed = false;
        try {
            access.article(key.articleId());
            if (pit == null) pit = index.open();
            List<SearchCursor.Value> after = cursor == null ? List.of() : cursor.after();
            var found = new ArrayList<CommentRow>(size);
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                var candidates = index.page(key, pit, after, size + 1);
                pit = candidates.pit();
                var rows = values.objects(candidates.hits().stream().map(CommentIndex.Hit::id).toList());
                for (var hit : candidates.hits()) {
                    var row = rows.get(hit.id());
                    if (row != null && row.articleId() == key.articleId() && row.rootId() == 0 && !row.deleted()) {
                        // 候选仅作下一页探测，游标停在实际消费的上一条。
                        if (found.size() == size) {
                            if (deadline <= clock.millis()) throw CommentErrors.expired();
                            var next = PREFIX + tokens.seal(new Cursor(key, pit, after, rank, deadline));
                            var response = response(found, next, true, rank, user);
                            completed = true;
                            return response;
                        }
                        found.add(row);
                    }
                    after = hit.sort();
                }
                if (candidates.hits().size() < size + 1) {
                    var response = response(found, null, false, rank, user);
                    index.close(pit);
                    completed = true;
                    return response;
                }
            }
            throw CommentErrors.unavailable();
        } catch (ElasticsearchException failure) {
            if (cursor != null && failure.status() == 404) throw CommentErrors.expired();
            throw CommentErrors.unavailable();
        } catch (IOException | DataAccessException failure) {
            throw CommentErrors.unavailable();
        } catch (ApiException failure) {
            if (failure.status().is5xxServerError()) throw CommentErrors.unavailable();
            throw failure;
        } finally {
            if (!completed && cursor == null && pit != null) index.close(pit);
        }
    }

    private CommentLists.Response response(List<CommentRow> rows, String next, boolean more, String rank, Long user) {
        var previews = replies.previews(rows, user);
        var items = views.items(rows, user).stream()
                .map(item -> item.withReplies(previews.get(Long.parseLong(item.id())))).toList();
        return new CommentLists.Response(items, next, more, rank);
    }

    private Cursor decode(CommentListKey key, String encoded) {
        if (!encoded.startsWith(PREFIX)) {
            tokens.decode(key, encoded);
            throw CommentErrors.expired();
        }
        var cursor = tokens.open(encoded.substring(PREFIX.length()), Cursor.class, 16384);
        if (!key.equals(cursor.key()) || cursor.pit() == null || cursor.pit().isBlank()
                || cursor.after() == null || cursor.after().size() != 3
                || (key.sort() == CommentListKey.Sort.HOT) != (cursor.rankVersion() != null)) {
            throw CommentErrors.invalid();
        }
        if (cursor.expiresAt() <= clock.millis()) throw CommentErrors.expired();
        return cursor;
    }

    record Cursor(CommentListKey key, String pit, List<SearchCursor.Value> after, String rankVersion, long expiresAt) { }
}
