package com.febrie.backend.support.persistence;

import org.apache.ibatis.annotations.Param;

/** 文章测试清理仅按本次创建的作者执行，不向生产Repository增加物理删除入口。 */
public interface ArticleFixtureMapper {
    /** 事务结果断言专用，不为测试保留生产列表的COUNT入口。 */
    long countPublic(@Param("author") long author);
    int deleteLikeStatesForAuthor(@Param("author") long author);
    int deleteLikeCountsForAuthor(@Param("author") long author);
    int deleteTagsForAuthor(@Param("author") long author);
    /** 先清本次作者的事务待办，避免测试遗留数据进入后续领取用例。 */
    default int deleteArticlesForAuthor(long author) {
        deleteOutboxForAuthor(author);
        deleteVisibilityRepairsForAuthor(author);
        return deleteArticleRowsForAuthor(author);
    }
    int deleteOutboxForAuthor(@Param("author") long author);
    int deleteVisibilityRepairsForAuthor(@Param("author") long author);
    int deleteArticleRowsForAuthor(@Param("author") long author);
    int deleteTag(@Param("id") long id);
    void lockWaitSeconds(@Param("seconds") int seconds);
}
