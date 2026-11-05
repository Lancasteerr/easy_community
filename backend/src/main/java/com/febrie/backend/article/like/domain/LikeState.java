package com.febrie.backend.article.like.domain;

/** 主库或缓存快照；version=0仅表示从未持久化关系，不用于正式命令。 */
public record LikeState(long userId, long articleId, boolean liked, long version) { }
