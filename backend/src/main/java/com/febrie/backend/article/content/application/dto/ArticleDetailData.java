package com.febrie.backend.article.content.application.dto;

import java.time.Instant;
import java.util.List;

/** 公开详情缓存不包含作者资料、生成地址、编辑JSON或访问者状态。 */
public record ArticleDetailData(String articleId, String title, String authorId, String summary,
                                String coverMediaId, List<Long> tagIds, Instant publishedAt,
                                Instant updatedAt, String contentHtml) {
    public ArticleDetailData { tagIds = List.copyOf(tagIds); }
}
