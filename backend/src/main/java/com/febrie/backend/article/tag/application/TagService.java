package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.user.application.dto.UserActor;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** HTTP编排不持事务，提交后的目录错误不撤销新增结果。 */
@Service
@Transactional(propagation = Propagation.NEVER)
public class TagService {
    private final TagCreationService creation;
    private final TagCatalog catalog;
    public TagService(TagCreationService creation, TagCatalog catalog) { this.creation = creation; this.catalog = catalog; }
    public List<TagView> search(String q, int limit) { return catalog.search(q, limit); }
    public TagView create(UserActor actor, String name) {
        var result = creation.create(actor, name);
        catalog.refreshBestEffort();
        return result;
    }
}
