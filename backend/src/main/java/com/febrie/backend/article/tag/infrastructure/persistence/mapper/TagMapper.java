package com.febrie.backend.article.tag.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.febrie.backend.article.tag.infrastructure.persistence.entity.TagEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 普通目录操作复用MyBatis-Plus。 */
public interface TagMapper extends BaseMapper<TagEntity> {
    /** 目录查询限制语句耗时，且不使用MyBatis二级缓存。 */
    @Select("SELECT version FROM tag_dictionary_meta WHERE id = 1")
    @Options(timeout = 2, useCache = false)
    Long directoryVersion();

    @Select("SELECT id, name FROM tag ORDER BY id")
    @Options(timeout = 2, useCache = false)
    List<TagEntity> directory();

    @Update("UPDATE tag_dictionary_meta SET version = version + 1 WHERE id = 1")
    @Options(timeout = 2)
    int advanceDirectoryVersion();
}
