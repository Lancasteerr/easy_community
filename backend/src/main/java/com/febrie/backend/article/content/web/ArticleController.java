package com.febrie.backend.article.content.web;

import com.febrie.backend.article.common.ArticleIds;
import com.febrie.backend.article.content.application.ArticleQueryService;
import com.febrie.backend.article.content.application.ArticleService;
import com.febrie.backend.article.content.application.dto.ArticleEditView;

import com.febrie.backend.article.content.web.request.ArticleSaveRequest;
import com.febrie.backend.article.content.web.response.ArticleSaveResponse;
import com.febrie.backend.article.content.web.response.ArticleLikeAssembler;
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
    private final ArticleLikeAssembler likes;
    public ArticleController(ArticleService articles, ArticleQueryService queries, ObjectMapper mapper, ArticleLikeAssembler likes) {
        this.articles = articles; this.queries = queries; this.mapper = mapper; this.likes=likes;
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
    public Object detail(@PathVariable String id,@AuthenticationPrincipal SessionPrincipal principal) {
        return likes.detail(queries.detail(ArticleIds.parse(id)),principal==null?null:principal.userId());
    }
    @GetMapping("/api/articles/{id}/edit")
    public ArticleEditView edit(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        return queries.edit(actor(principal), ArticleIds.parse(id));
    }
    @GetMapping("/api/users/me/articles")
    public Object page(@AuthenticationPrincipal SessionPrincipal principal,
                           @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return likes.page(queries.page(actor(principal), page, size),principal.userId());
    }
    private static UserActor actor(SessionPrincipal principal) {
        return new UserActor(principal.userId(), principal.role(), principal.authVersion());
    }
}
