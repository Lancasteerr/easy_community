package com.febrie.backend.article.tag.infrastructure.persistence;

import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.article.tag.infrastructure.persistence.entity.TagEntity;
import com.febrie.backend.article.tag.infrastructure.persistence.mapper.TagMapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 标签只增加不删除，所有插入都与目录版本一起提交。 */
@Repository
public class TagRepository {
    private final TagMapper tags;
    public TagRepository(TagMapper tags) { this.tags = tags; }
    public List<TagView> all() {
        return tags.directory().stream()
                .map(row -> new TagView(row.getId().toString(), row.getName())).toList();
    }
    @Transactional
    public long insert(String name) {
        var row = new TagEntity();
        row.setName(name);
        tags.insert(row);
        if (tags.advanceDirectoryVersion() != 1) { throw new IllegalStateException("标签目录版本缺失"); }
        return row.getId();
    }
    public long version() {
        Long version = tags.directoryVersion();
        if (version == null || version < 1) { throw new IllegalStateException("标签目录版本无效"); }
        return version;
    }
}
