package com.febrie.backend.support.persistence;

import org.apache.ibatis.annotations.Param;

/** 文章测试清理仅按本次创建的作者执行，不向生产Repository增加物理删除入口。 */
public interface ArticleFixtureMapper {
    int deleteLikeStatesForAuthor(@Param("author") long author);
    int deleteLikeCountsForAuthor(@Param("author") long author);
    int deleteTagsForAuthor(@Param("author") long author);
    /** 先清本次作者的事务事件，避免测试遗留事件进入后续Relay用例。 */
    default int deleteArticlesForAuthor(long author) {
        deleteOutboxForAuthor(author);
        return deleteArticleRowsForAuthor(author);
    }
    int deleteOutboxForAuthor(@Param("author") long author);
    int deleteArticleRowsForAuthor(@Param("author") long author);
    int deleteTag(@Param("id") long id);
    void lockWaitSeconds(@Param("seconds") int seconds);
}
