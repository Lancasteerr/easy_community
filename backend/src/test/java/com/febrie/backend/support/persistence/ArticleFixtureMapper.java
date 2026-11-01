package com.febrie.backend.support.persistence;

import org.apache.ibatis.annotations.Param;

/** 文章测试清理仅按本次创建的作者执行，不向生产Repository增加物理删除入口。 */
public interface ArticleFixtureMapper {
    int deleteTagsForAuthor(@Param("author") long author);
    int deleteArticlesForAuthor(@Param("author") long author);
    int deleteTag(@Param("id") long id);
    void lockWaitSeconds(@Param("seconds") int seconds);
}
