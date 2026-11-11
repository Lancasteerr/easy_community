package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.article.tag.infrastructure.persistence.TagRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 独立主库读取不继承外层快照；完整目录和版本来自同一RR视图。 */
@Service
public class TagDirectoryReader {
    private final TagRepository tags;
    public TagDirectoryReader(TagRepository tags) { this.tags = tags; }
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public long version() { return tags.version(); }
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Directory load() { return new Directory(tags.version(), tags.all()); }

    /** 事务返回后才构建搜索字段，缩短数据库连接占用时间。 */
    public record Directory(long version, List<TagView> tags) {
        public Directory { tags = List.copyOf(tags); }
    }
}
