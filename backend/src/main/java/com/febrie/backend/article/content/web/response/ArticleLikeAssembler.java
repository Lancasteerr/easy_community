package com.febrie.backend.article.content.web.response;

import com.febrie.backend.article.content.application.dto.ArticleDetailView;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.febrie.backend.article.like.application.LikeQueryService;
import java.util.List;
import org.springframework.stereotype.Component;

/** 个人字段仅存在于最终响应中，禁止写进共享文章投影或编辑响应。 */
@Component
public class ArticleLikeAssembler {
    private final LikeQueryService likes;
    public ArticleLikeAssembler(LikeQueryService likes){this.likes=likes;}
    public Detail detail(ArticleDetailView article,Long user){
        long articleId=Long.parseLong(article.articleId());
        var summary=likes.summaries(user,List.of(articleId)).get(articleId);
        return new Detail(article,summary.likeCount(),summary.likedByMe());
    }
    public record Detail(@JsonUnwrapped ArticleDetailView article,long likeCount,boolean likedByMe) { }
}
