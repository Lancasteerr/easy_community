package com.febrie.backend.article.like.application;

import com.febrie.backend.article.like.domain.LikeCommand;

/** 可失败的展示投影端口，不参与SQL事务或消息持久受理判定。 */
public interface LikeProjection {
    void pending(LikeCommand command);
    /** 单条提交复用文章投影，批内计数失效由文章入口去重。 */
    default void committed(LikeSqlService.Result result) {
        committedArticle(new LikeSqlService.ArticleResult(result.state().articleId(), java.util.List.of(result), 0));
    }
    void committedArticle(LikeSqlService.ArticleResult result);
}
