package com.febrie.backend.article.search.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.febrie.backend.article.content.application.ArticleDisplayAssembler;
import com.febrie.backend.article.content.application.dto.ArticleListView;
import com.febrie.backend.article.like.application.LikeQueryService;
import com.febrie.backend.article.search.application.ArticleSearchService;
import com.febrie.backend.article.view.application.ArticleViewService;
import java.util.List;
import org.springframework.stereotype.Component;

/** 只装配最终卡片的计数和访问者字段，不把个人状态写入共享缓存。 */
@Component
public class ArticleSearchAssembler {
    private final ArticleViewService views;
    private final LikeQueryService likes;
    private final ArticleDisplayAssembler display;
    public ArticleSearchAssembler(ArticleViewService views, LikeQueryService likes, ArticleDisplayAssembler display) {
        this.views = views; this.likes = likes; this.display = display;
    }
    public Response assemble(ArticleSearchService.Page page, Long user) {
        var articles = display.lists(page.items());
        var ids = articles.stream().map(v -> Long.parseLong(v.articleId())).toList();
        var totals = views.totals(ids);
        var summaries = likes.summaries(user, ids);
        return new Response(articles.stream().map(v -> {
            long id = Long.parseLong(v.articleId()); var summary = summaries.get(id);
            return new Card(v, totals.get(id), summary.likeCount(), summary.likedByMe());
        }).toList(), page.nextCursor());
    }
    public record Card(@JsonUnwrapped ArticleListView article, long viewCount, long likeCount, boolean likedByMe) { }
    public record Response(List<Card> items, String nextCursor) { }
}
