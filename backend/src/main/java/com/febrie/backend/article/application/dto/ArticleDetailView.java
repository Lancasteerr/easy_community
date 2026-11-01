package com.febrie.backend.article.application.dto;

import java.time.Instant;
import java.util.List;

/** 详情投影与列表分开缓存，不包含编辑JSON和任何会话私有标志。 */
public record ArticleDetailView(String articleId, String title, String authorId, String authorName,
                                String summary, String coverMediaId, String coverUrl, List<TagView> tags,
                                Instant publishedAt, Instant updatedAt, String contentHtml, Long viewCount) {
    public ArticleDetailView { tags = List.copyOf(tags); }

    /** 正文缓存不携带总量；每次响应以实时统计生成独立副本。 */
    public ArticleDetailView withViewCount(long count) {
        return new ArticleDetailView(articleId, title, authorId, authorName, summary, coverMediaId, coverUrl,
                tags, publishedAt, updatedAt, contentHtml, count);
    }
}
