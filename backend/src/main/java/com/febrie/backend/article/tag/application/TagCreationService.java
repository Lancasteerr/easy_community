package com.febrie.backend.article.tag.application;

import com.febrie.backend.article.common.ArticleErrors;
import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.article.tag.infrastructure.persistence.TagRepository;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import com.febrie.backend.user.domain.Role;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 事务代理确认提交后，编排层才能刷新目录。 */
@Service
public class TagCreationService {
    private final TagRepository tags;
    public TagCreationService(TagRepository tags) { this.tags = tags; }
    @Transactional
    public TagView create(UserActor actor, String name) {
        if (actor.role() != Role.ADMIN) { throw ArticleErrors.forbidden(); }
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 32) { throw ArticleErrors.invalid(); }
        try { return new TagView(Long.toString(tags.insert(normalized)), normalized); }
        catch (DuplicateKeyException error) { throw new ApiException(HttpStatus.CONFLICT, "TAG_EXISTS", "标签名称已存在"); }
    }
}
