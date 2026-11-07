package com.febrie.backend.article.search.application;

import java.time.Instant;
import java.util.List;

/** 主库同一短快照中的小字段；不包含正文、计数或展示名称。 */
public record ArticleIndexMetadata(long articleId, String title, Instant publishedAt, boolean visible,
                                   long metaVersion, List<String> tagIds) {
    public ArticleIndexMetadata { tagIds = List.copyOf(tagIds); }
}
