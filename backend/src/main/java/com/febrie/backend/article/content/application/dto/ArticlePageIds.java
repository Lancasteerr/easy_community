package com.febrie.backend.article.content.application.dto;

import java.util.List;

/** 主库选出的本人公开文章ID，不能缓存整页身份或排序。 */
public record ArticlePageIds(List<Long> ids, long total) {
    public ArticlePageIds { ids = List.copyOf(ids); }
}
