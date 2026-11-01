package com.febrie.backend.article.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.febrie.backend.article.application.dto.TagView;
import com.febrie.backend.article.infrastructure.persistence.entity.TagEntity;
import com.febrie.backend.article.infrastructure.persistence.mapper.TagMapper;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;

/** 标签只增加不删除，验证目标集合不需要扩展状态锁协议。 */
@Repository
public class TagRepository {
    private final TagMapper tags;
    public TagRepository(TagMapper tags) { this.tags = tags; }
    public List<TagView> all() {
        return tags.selectList(Wrappers.<TagEntity>lambdaQuery().orderByAsc(TagEntity::getId)).stream()
                .map(row -> new TagView(row.getId().toString(), row.getName())).toList();
    }
    public long insert(String name) {
        var row = new TagEntity();
        row.setName(name);
        tags.insert(row);
        return row.getId();
    }
    public boolean allExist(Collection<Long> ids) {
        return ids.isEmpty() || tags.selectCount(Wrappers.<TagEntity>lambdaQuery().in(TagEntity::getId, ids)) == ids.size();
    }
}
