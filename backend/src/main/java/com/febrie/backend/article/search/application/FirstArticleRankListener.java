package com.febrie.backend.article.search.application;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 本地通知只提交到有界执行器，不能阻塞已完成的元数据消费或替代全量校准。 */
@Component
public class FirstArticleRankListener {
    private final ArticleRankService ranks;
    public FirstArticleRankListener(ArticleRankService ranks) { this.ranks = ranks; }
    @EventListener public void created(ArticleIndexed event) { ranks.tryFirst(event.articleId()); }
}
