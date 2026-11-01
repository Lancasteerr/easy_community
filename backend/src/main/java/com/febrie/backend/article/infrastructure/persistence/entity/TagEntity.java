package com.febrie.backend.article.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

/** 最小标签目录映射，名称创建后不变。 */
@Getter
@Setter
@TableName("tag")
public class TagEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
}
