package com.febrie.backend.article.comment;

import com.febrie.backend.article.comment.infrastructure.persistence.CommentRow;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.like.domain.LikeTarget.Type;
import com.febrie.backend.media.application.MediaUrls;
import com.febrie.backend.user.application.UserBasicQueryService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 公开视图只装配当前页作者与点赞；个人状态从不写进共享列表快照。 */
@Component
public class CommentViews {
    private final UserBasicQueryService authors;
    private final LikeQueryService likes;
    private final MediaUrls mediaUrls;

    public CommentViews(UserBasicQueryService authors, LikeQueryService likes, MediaUrls mediaUrls) {
        this.authors = authors;
        this.likes = likes;
        this.mediaUrls = mediaUrls;
    }

    List<Item> items(List<CommentRow> rows, Long user) {
        var active = rows.stream().filter(row -> !row.deleted()).toList();
        var authorIds = active.stream().map(CommentRow::authorId).distinct().toList();
        var authorsById = authors.get(authorIds);
        var roots = active.stream().filter(row -> row.rootId() == 0).map(CommentRow::id).toList();
        Map<Long, LikeQueryService.Summary> summaries = likes.summaries(Type.COMMENT, user, roots);
        return active.stream().map(row -> {
            var author = authorsById.get(row.authorId());
            if (author == null) throw CommentErrors.unavailable();
            // 复用批量查询出的头像 ID，仅在一级评论展示时生成公开地址。
            String avatarUrl = row.rootId() == 0 && author.avatarMediaId() != null
                    ? mediaUrls.publicUrl(author.avatarMediaId()) : null;
            return view(row, new Author(text(row.authorId()), author.username(), avatarUrl), summaries.get(row.id()));
        }).toList();
    }

    Item created(CommentRow row, String username) {
        return view(row, new Author(text(row.authorId()), username, null),
                row.rootId() == 0 ? new LikeQueryService.Summary(0, false) : null);
    }

    private static Item view(CommentRow row, Author author, LikeQueryService.Summary likes) {
        return new Item(text(row.id()), row.rootId() == 0 ? null : text(row.rootId()),
                row.replyToId() == null ? null : text(row.replyToId()), author, row.content(), row.createdAt(),
                "ACTIVE", likes == null ? null : likes.likeCount(),
                likes == null ? null : likes.likedByMe(), null);
    }

    private static String text(long id) { return Long.toString(id); }

    public record Author(String id, String username, @JsonInclude(JsonInclude.Include.NON_NULL) String avatarUrl) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, @JsonInclude(JsonInclude.Include.ALWAYS) String rootId,
                       @JsonInclude(JsonInclude.Include.ALWAYS) String replyToId, Author author, String content,
                       Instant createdAt, String status, Long likeCount, Boolean likedByMe, CommentLists.Response replies) {
        Item withReplies(CommentLists.Response preview) {
            return new Item(id, rootId, replyToId, author, content, createdAt, status, likeCount, likedByMe, preview);
        }
    }
}
