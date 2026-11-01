package com.febrie.backend.article.application.dto;

import java.util.List;
import java.util.Set;

/** 同一规范化正文派生的不可变目标，不携带会变化的旧引用或权限快照。 */
public record PreparedArticle(String title, String summary, String contentJson, String contentHtml,
                              String coverMediaId, Set<String> bodyMediaIds, List<Long> tagIds) {
    public PreparedArticle {
        bodyMediaIds = Set.copyOf(bodyMediaIds);
        tagIds = List.copyOf(tagIds);
    }
}
