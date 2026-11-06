package com.febrie.backend.article.content.infrastructure.persistence.mapper;

import com.febrie.backend.article.content.infrastructure.persistence.entity.ArticleEntity;
import com.febrie.backend.article.content.infrastructure.persistence.entity.ArticleTagRow;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 锁、部分字段投影与关系维护在XML中显式表达，不伪造关系表单列主键。 */
public interface ArticleMapper extends BaseMapper<ArticleEntity> {
    int initializeLikeCount(@Param("id") long id);
    ArticleEntity lock(@Param("id") long id);
    List<ArticleEntity> listRows(@Param("ids") Collection<Long> ids);
    List<Long> pageIds(@Param("author") long author, @Param("offset") long offset, @Param("size") int size);
    long countPublic(@Param("author") long author);
    List<ArticleTagRow> tags(@Param("ids") Collection<Long> ids);
    int removeTags(@Param("id") long id);
    int addTags(@Param("id") long id, @Param("tags") Collection<Long> tags);
    List<Long> hiddenIds(@Param("after") long after, @Param("size") int size);
}
