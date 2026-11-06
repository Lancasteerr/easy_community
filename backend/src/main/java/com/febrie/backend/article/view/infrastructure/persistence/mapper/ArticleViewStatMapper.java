package com.febrie.backend.article.view.infrastructure.persistence.mapper;

import com.febrie.backend.article.view.infrastructure.persistence.entity.ArticleViewCountRow;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 日统计锁与总量增量属于同一SQL事务，不接触Redis。 */
public interface ArticleViewStatMapper {
    Long selectPvForUpdate(@Param("articleId") long articleId, @Param("date") LocalDate date);
    int upsertPv(@Param("articleId") long articleId, @Param("date") LocalDate date, @Param("pv") long pv);
    int incrementTotal(@Param("articleId") long articleId, @Param("delta") long delta);
    Long selectTotal(@Param("articleId") long articleId);
    List<ArticleViewCountRow> selectTotals(@Param("ids") Collection<Long> ids);
}
