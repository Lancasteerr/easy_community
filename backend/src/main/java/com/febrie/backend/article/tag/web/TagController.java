package com.febrie.backend.article.tag.web;

import com.febrie.backend.article.tag.application.TagService;
import com.febrie.backend.article.tag.application.dto.TagView;
import com.febrie.backend.article.tag.web.request.TagRequest;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.user.application.dto.UserActor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 目录只提供录入所需读和管理员新增；URL权限与业务权限双重明确。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TagController {
    private final TagService tags;
    public TagController(TagService tags) { this.tags = tags; }
    @GetMapping("/api/tags")
    public List<TagView> search(@RequestParam(required = false) String q, @RequestParam(defaultValue = "10") int limit) {
        return tags.search(q, limit);
    }
    @PostMapping("/api/admin/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagView create(@AuthenticationPrincipal SessionPrincipal principal, @Valid @RequestBody TagRequest request) {
        return tags.create(new UserActor(principal.userId(), principal.role(), principal.authVersion()), request.name());
    }
}
