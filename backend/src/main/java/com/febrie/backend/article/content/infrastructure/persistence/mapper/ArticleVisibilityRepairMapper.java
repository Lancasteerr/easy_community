package com.febrie.backend.article.content.infrastructure.persistence.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 待办按文章去重，领取和延后在同一短事务中完成。 */
public interface ArticleVisibilityRepairMapper {
    void enqueue(@Param("articleId") long articleId);
    List<Long> available();
    int defer(@Param("ids") List<Long> ids);
    void complete(@Param("articleId") long articleId);
}
