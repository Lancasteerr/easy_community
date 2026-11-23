package com.febrie.backend.article.like.domain;

/** 点赞目标的命名空间，同一个数字可以同时代表文章和评论。 */
public record LikeTarget(Type type, long id) {
    public LikeTarget {
        if (type == null || id <= 0) {
            throw new IllegalArgumentException("点赞目标无效");
        }
    }

    public enum Type { ARTICLE, COMMENT }
}
