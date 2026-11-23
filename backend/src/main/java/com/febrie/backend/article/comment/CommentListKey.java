package com.febrie.backend.article.comment;

/** 一个列表仅对应一个文章、楼层和排序，命令与游标都需要校验归属。 */
public record CommentListKey(long articleId, long rootId, Sort sort) {
    public CommentListKey {
        if (articleId <= 0 || rootId < 0 || sort == null || ((rootId > 0) != (sort == Sort.REPLIES))) {
            throw new IllegalArgumentException("评论列表范围不合法");
        }
    }

    public enum Sort { LATEST, HOT, REPLIES }
}
