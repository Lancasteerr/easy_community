package com.febrie.backend.article.like.infrastructure.persistence.mapper;

import com.febrie.backend.article.like.domain.LikeState;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 只声明主库语句；事务和重试由外层服务决定。 */
public interface LikeMapper {
    int initializeState(@Param("userId") long userId, @Param("articleId") long articleId);
    LikeState lock(@Param("userId") long userId, @Param("articleId") long articleId);
    int update(@Param("state") LikeState state);
    int addDelta(@Param("articleId") long articleId, @Param("delta") int delta);
    boolean publicArticle(@Param("articleId") long articleId);
    List<LikeState> states(@Param("userId") long userId, @Param("ids") Collection<Long> ids);
    List<CountRow> counts(@Param("ids") Collection<Long> ids);

    /** 顺序与SQL列保持一致，避免可变实体被更新后丢失旧值。 */
    record CountRow(long articleId, long likeCount) { }
}
