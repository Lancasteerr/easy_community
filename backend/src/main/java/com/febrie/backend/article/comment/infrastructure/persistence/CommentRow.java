package com.febrie.backend.article.comment.infrastructure.persistence;

import java.time.Instant;

/** 主库评论与对象缓存共用的不可变数据；一级评论的 rootId 为 0。 */
public record CommentRow(long id, long articleId, long rootId, Long replyToId, long authorId,
                         String content, Instant createdAt, boolean deleted) { }
