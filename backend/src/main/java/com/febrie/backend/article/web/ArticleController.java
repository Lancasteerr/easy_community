package com.febrie.backend.article.web;

import com.febrie.backend.article.application.*;
import com.febrie.backend.article.application.dto.*;
import com.febrie.backend.article.web.request.ArticleSaveRequest;
import com.febrie.backend.article.web.response.ArticleSaveResponse;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.user.application.dto.UserActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** 身份只从服务端Session转换，不接触Mapper、Redis或对象存储。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleController {
    private final ArticleService articles;
    private final ArticleQueryService queries;
    private final ObjectMapper mapper;
    public ArticleController(ArticleService articles, ArticleQueryService queries, ObjectMapper mapper) {
        this.articles = articles; this.queries = queries; this.mapper = mapper;
    }
    @PostMapping(value = "/api/articles", consumes = "application/json")
    public ArticleSaveResponse save(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody byte[] body) {
        long id = articles.save(actor(principal), ArticleSaveRequest.parse(body, mapper));
        return new ArticleSaveResponse(Long.toString(id));
    }
    @DeleteMapping("/api/articles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        articles.hide(actor(principal), ArticleIds.parse(id));
    }
    @GetMapping("/api/articles/{id}")
    public ArticleDetailView detail(@PathVariable String id) { return queries.detail(ArticleIds.parse(id)); }
    @GetMapping("/api/articles/{id}/edit")
    public ArticleEditView edit(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        return queries.edit(actor(principal), ArticleIds.parse(id));
    }
    @GetMapping("/api/users/me/articles")
    public ArticlePage page(@AuthenticationPrincipal SessionPrincipal principal,
                           @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return queries.page(actor(principal), page, size);
    }
    private static UserActor actor(SessionPrincipal principal) {
        return new UserActor(principal.userId(), principal.role(), principal.authVersion());
    }
}
