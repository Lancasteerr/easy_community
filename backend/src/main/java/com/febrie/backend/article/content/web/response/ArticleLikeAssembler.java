package com.febrie.backend.article.content.web.response;

import com.febrie.backend.article.content.application.dto.ArticleDetailView;
import com.febrie.backend.article.content.application.dto.ArticleListView;
import com.febrie.backend.article.content.application.dto.ArticlePage;

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
    public Page page(ArticlePage page,long user){
        var values=likes.summaries(user,page.items().stream().map(item->Long.parseLong(item.articleId())).toList());
        var items=page.items().stream().map(item->{var summary=values.get(Long.parseLong(item.articleId()));return new Card(item,summary.likeCount(),summary.likedByMe());}).toList();
        return new Page(items,page.page(),page.size(),page.total());
    }
    public record Detail(@JsonUnwrapped ArticleDetailView article,long likeCount,boolean likedByMe) { }
    public record Card(@JsonUnwrapped ArticleListView article,long likeCount,boolean likedByMe) { }
    public record Page(List<Card> items,int page,int size,long total) { }
}
