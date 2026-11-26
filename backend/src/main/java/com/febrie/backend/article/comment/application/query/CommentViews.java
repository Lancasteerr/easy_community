package com.febrie.backend.article.comment.application.query;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.febrie.backend.article.comment.infrastructure.persistence.CommentStore.ReplyTarget;
import com.febrie.backend.article.comment.infrastructure.redis.CommentValues;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.like.domain.LikeTarget.Type;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.UserBasicQueryService;
import com.febrie.backend.user.application.dto.UserBasicData;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/** 评论视图只装配当前页的参与者和点赞信息，个人状态从不写进共享列表快照。 */
@Component
public class CommentViews {
    private static final Logger log = LoggerFactory.getLogger(CommentViews.class);
    private final UserBasicQueryService authors;
    private final LikeQueryService likes;
    private final MediaUrls mediaUrls;
    private final CommentValues values;

    public CommentViews(UserBasicQueryService authors, LikeQueryService likes, MediaUrls mediaUrls,
                        CommentValues values) {
        this.authors = authors;
        this.likes = likes;
        this.mediaUrls = mediaUrls;
        this.values = values;
    }

    List<Item> items(List<CommentRow> rows, Long user) {
        var active = rows.stream().filter(row -> !row.deleted()).toList();
        var authorIds = active.stream().map(CommentRow::authorId).distinct().toList();
        var targetAuthors = targetAuthors(active);
        var authorsById = profiles(authorIds, targetAuthors.values());
        var roots = active.stream().filter(row -> row.rootId() == 0).map(CommentRow::id).toList();
        Map<Long, LikeQueryService.Summary> summaries = likes.summaries(Type.COMMENT, user, roots);
        return active.stream().map(row -> {
            var author = authorsById.get(row.authorId());
            if (author == null) throw CommentErrors.unavailable();
            // 复用批量查询出的头像 ID，仅在一级评论展示时生成公开地址。
            String avatarUrl = row.rootId() == 0 && author.avatarMediaId() != null
                    ? mediaUrls.publicUrl(author.avatarMediaId()) : null;
            Long targetAuthorId = targetAuthors.get(row.id());
            var targetAuthor = targetAuthorId == null ? null : authorsById.get(targetAuthorId);
            return view(row, new Author(text(row.authorId()), author.username(), avatarUrl),
                    targetAuthor == null ? null : targetAuthor.username(), summaries.get(row.id()));
        }).toList();
    }

    /** 首次发布直接使用已提交的回复及写入时取得的目标作者 ID。 */
    public Item createdReply(CommentRow row, String username, long targetAuthorId) {
        var author = new Author(text(row.authorId()), username, null);
        if (!repliesToAnotherReply(row)) return view(row, author, null, null);

        String targetUsername = username;
        if (targetAuthorId != row.authorId()) {
            try {
                targetUsername = authors.get(targetAuthorId).map(UserBasicData::username).orElse(null);
            } catch (ApiException failure) {
                if (!failure.status().is5xxServerError()) throw failure;
                targetUsername = null;
            }
        }
        return view(row, author, targetUsername, null);
    }

    /** 首次发布使用事务结果，避免为刚创建的一级评论查询资料和点赞。 */
    public Item submitted(CommentRow row, String username) {
        var author = new Author(text(row.authorId()), username, null);
        var likes = row.rootId() == 0 ? new LikeQueryService.Summary(0, false) : null;
        return view(row, author, null, likes);
    }

    /** 重放时，调用方已经校验该评论当前可见。 */
    public Item published(CommentRow row, long user) {
        return items(List.of(row), user).getFirst();
    }

    private Map<Long, Long> targetAuthors(List<CommentRow> rows) {
        var targetIds = rows.stream().filter(CommentViews::repliesToAnotherReply)
                .map(CommentRow::replyToId).distinct().toList();
        if (targetIds.isEmpty()) return Map.of();

        Map<Long, ReplyTarget> targets;
        try {
            targets = values.replyTargets(targetIds);
        } catch (DataAccessException failure) {
            log.warn("回复目标资料暂不可用 count={}", targetIds.size(), failure);
            return Map.of();
        } catch (ApiException failure) {
            if (!failure.status().is5xxServerError()) throw failure;
            log.warn("回复目标资料暂不可用 count={} code={}", targetIds.size(), failure.code());
            return Map.of();
        }

        // 保留已软删除目标的账号名，同时防止异常历史数据引用其它文章或楼层的作者。
        var result = new LinkedHashMap<Long, Long>();
        for (var row : rows) {
            if (!repliesToAnotherReply(row)) continue;
            var target = targets.get(row.replyToId());
            if (target != null && target.articleId() == row.articleId() && target.rootId() == row.rootId()) {
                result.put(row.id(), target.authorId());
            }
        }
        return result;
    }

    /** 直接回复楼主时目标 ID 等于楼层 ID，只为楼内回复展示目标账号名。 */
    private static boolean repliesToAnotherReply(CommentRow row) {
        return row.replyToId() != null && row.replyToId() != row.rootId();
    }

    private Map<Long, UserBasicData> profiles(List<Long> authorIds, Collection<Long> targetAuthorIds) {
        var ids = new LinkedHashSet<>(authorIds);
        boolean hasExtraTargets = ids.addAll(targetAuthorIds);
        try {
            return authors.get(List.copyOf(ids));
        } catch (ApiException failure) {
            if (!hasExtraTargets || !failure.status().is5xxServerError()) throw failure;
            // 可选的回复对象资料异常时，仍用原有路径装配评论作者。
            return authors.get(authorIds);
        }
    }

    private static Item view(CommentRow row, Author author, String replyToUsername, LikeQueryService.Summary likes) {
        return new Item(text(row.id()), row.rootId() == 0 ? null : text(row.rootId()),
                replyToUsername, author, row.content(), row.createdAt(),
                "ACTIVE", likes == null ? null : likes.likeCount(),
                likes == null ? null : likes.likedByMe(), null);
    }

    private static String text(long id) { return Long.toString(id); }

    public record Author(String id, String username, @JsonInclude(JsonInclude.Include.NON_NULL) String avatarUrl) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, @JsonInclude(JsonInclude.Include.ALWAYS) String rootId,
                       String replyToUsername, Author author, String content,
                       Instant createdAt, String status, Long likeCount, Boolean likedByMe, CommentLists.Response replies) {
        Item withReplies(CommentLists.Response preview) {
            return new Item(id, rootId, replyToUsername, author, content, createdAt, status, likeCount, likedByMe, preview);
        }
    }
}
