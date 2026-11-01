package com.febrie.backend.article.application;

import com.febrie.backend.article.application.dto.TagView;
import com.febrie.backend.article.infrastructure.persistence.TagRepository;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提供文章录入所需最小目录，不增加改名、删除或排序行为。 */
@Service
public class TagService {
    private final TagRepository tags;
    public TagService(TagRepository tags) { this.tags = tags; }
    public List<TagView> all() { return tags.all(); }
    @Transactional
    public TagView create(UserActor actor, String name) {
        if (actor.role() != Role.ADMIN) { throw ArticleErrors.forbidden(); }
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 32) { throw ArticleErrors.invalid(); }
        try { return new TagView(Long.toString(tags.insert(normalized)), normalized); }
        catch (DuplicateKeyException error) {
            throw new ApiException(HttpStatus.CONFLICT, "TAG_EXISTS", "标签名称已存在");
        }
    }
}
