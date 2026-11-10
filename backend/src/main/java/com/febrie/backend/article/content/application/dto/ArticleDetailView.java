package com.febrie.backend.article.content.application.dto;

import com.febrie.backend.article.tag.application.dto.TagView;

import java.time.Instant;
import java.util.List;

/** 最终公开详情响应；作者信息不进入文章缓存。 */
public record ArticleDetailView(String articleId, String title, String authorId, String authorName,
                                String summary, String coverMediaId, String coverUrl, List<TagView> tags,
                                Instant publishedAt, Instant updatedAt, String contentHtml, String authorAvatarUrl) {
    public ArticleDetailView { tags = List.copyOf(tags); }
}
