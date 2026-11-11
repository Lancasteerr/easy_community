package com.febrie.backend.article.content.infrastructure.persistence.mapper;

import com.febrie.backend.article.content.infrastructure.persistence.entity.ArticleEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 锁与小字段投影在XML中显式表达，标签集合随文章行一起保存。 */
public interface ArticleMapper extends BaseMapper<ArticleEntity> {
    int advanceMetaVersion(@Param("id") long id);
    int appendIndexEvent(@Param("id") long id);
    int initializeLikeCount(@Param("id") long id);
    ArticleEntity lock(@Param("id") long id);
    List<ArticleEntity> listRows(@Param("ids") Collection<Long> ids);
    List<Long> hiddenIds(@Param("after") long after, @Param("size") int size);
}
