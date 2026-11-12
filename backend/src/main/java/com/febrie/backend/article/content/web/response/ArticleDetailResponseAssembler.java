package com.febrie.backend.article.content.web.response;

import com.febrie.backend.article.content.application.dto.ArticleDetailView;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.view.application.ArticleViewService;
import java.util.List;
import org.springframework.stereotype.Component;

/** 阅读数、点赞数和个人状态仅在最终详情响应中组装，不回写共享文章投影。 */
@Component
public class ArticleDetailResponseAssembler {
    private final LikeQueryService likes;
    private final ArticleViewService views;

    public ArticleDetailResponseAssembler(LikeQueryService likes, ArticleViewService views) {
        this.likes = likes; this.views = views;
    }

    /** 文章已通过公开详情查询；此处只读取统计，本次PV由Controller在组装成功后记录。 */
    public Detail detail(ArticleDetailView article, Long user) {
        long articleId = Long.parseLong(article.articleId());
        var ids = List.of(articleId);
        var summary = likes.summaries(user, ids).get(articleId);
        long viewCount = views.totals(ids).get(articleId);
        return new Detail(article, viewCount, summary.likeCount(), summary.likedByMe());
    }

    public record Detail(@JsonUnwrapped ArticleDetailView article, long viewCount, long likeCount, boolean likedByMe) { }
}
