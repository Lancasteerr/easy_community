package com.febrie.backend.article.content.application.dto;

import com.febrie.backend.article.tag.application.dto.TagView;

import java.time.Instant;
import java.util.List;

/** 最终公开列表响应；作者资料在业务层组装，不作为缓存投影。 */
public record ArticleListView(String articleId, String title, String authorId, String authorName,
                              String summary, String coverUrl, List<TagView> tags, Instant publishedAt, Instant updatedAt, String authorAvatarUrl) {
    public ArticleListView { tags = List.copyOf(tags); }
}
