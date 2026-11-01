package com.febrie.backend.article.application.dto;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 只由已授权主库读取返回，不能进入共享公开缓存。 */
public record ArticleEditView(String articleId, String title, JsonNode content, String coverMediaId,
                              String coverUrl, List<TagView> tags, Instant createdAt, Instant updatedAt, Instant publishedAt) {
    public ArticleEditView { tags = List.copyOf(tags); }
}
