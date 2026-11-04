package com.febrie.backend.article.application.dto;

import java.time.Instant;
import java.util.List;

/** 可直接展示的公开列表投影，禁止夹带正文或访问者权限。 */
public record ArticleListView(String articleId, String title, String authorId, String authorName,
                              String summary, String coverUrl, List<TagView> tags, Instant publishedAt, Instant updatedAt) {
    public ArticleListView { tags = List.copyOf(tags); }
}
