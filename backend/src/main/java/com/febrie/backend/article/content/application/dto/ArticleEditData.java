package com.febrie.backend.article.content.application.dto;

import java.time.Instant;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 已授权的编辑数据只含文章字段；目录解析在SQL事务返回后执行。 */
public record ArticleEditData(String articleId, String title, JsonNode content, String coverMediaId,
                              List<Long> tagIds, Instant createdAt, Instant updatedAt, Instant publishedAt) {
    public ArticleEditData { tagIds = List.copyOf(tagIds); }
}
