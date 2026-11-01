package com.febrie.backend.article.application.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

/** 已通过HTTP格式校验的完整保存输入，身份另由服务器提供。 */
public record SaveArticleCommand(Long articleId, String title, JsonNode content, String coverMediaId, List<Long> tagIds) {
    public SaveArticleCommand { tagIds = List.copyOf(tagIds); }
}
