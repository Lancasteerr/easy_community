package com.febrie.backend.article.content.web;

import com.febrie.backend.article.common.ArticleIds;
import com.febrie.backend.article.content.application.ArticleQueryService;
import com.febrie.backend.article.content.application.ArticleService;
import com.febrie.backend.article.content.application.dto.ArticleEditView;

import com.febrie.backend.article.content.web.request.ArticleSaveRequest;
import com.febrie.backend.article.content.web.response.ArticleSaveResponse;
import com.febrie.backend.article.content.web.response.ArticleDetailResponseAssembler;
import com.febrie.backend.article.view.application.ArticleViewService;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.user.application.dto.UserActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

/** 身份只从服务端Session转换，不接触Mapper、Redis或对象存储。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleController {
    private static final Logger log = LoggerFactory.getLogger(ArticleController.class);
    private final ArticleService articles;
    private final ArticleQueryService queries;
    private final ObjectMapper mapper;
    private final ArticleDetailResponseAssembler responses;
    private final ArticleViewService views;
    public ArticleController(ArticleService articles, ArticleQueryService queries, ObjectMapper mapper,
                             ArticleDetailResponseAssembler responses, ArticleViewService views) {
        this.articles = articles; this.queries = queries; this.mapper = mapper; this.responses = responses;
        this.views = views;
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
    public ArticleDetailResponseAssembler.Detail detail(@PathVariable String id, @AuthenticationPrincipal SessionPrincipal principal) {
        long articleId = ArticleIds.parse(id);
        var response = responses.detail(queries.detail(articleId), principal == null ? null : principal.userId());
        // 先读统计并完成响应组装，再记录一次PV；响应阅读数不含本次加一，缓存命中同样计数。
        try {
            views.record(articleId);
        } catch (RuntimeException failure) {
            // 浏览统计允许漏计；不重试结果未知的加一，也不影响已取得的详情响应。
            log.warn("记录文章浏览失败 articleId={} error={}", articleId, failure.getClass().getSimpleName());
        }
        return response;
    }
    @GetMapping("/api/articles/{id}/edit")
    public ArticleEditView edit(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id) {
        return queries.edit(actor(principal), ArticleIds.parse(id));
    }
    private static UserActor actor(SessionPrincipal principal) {
        return new UserActor(principal.userId(), principal.role(), principal.authVersion());
    }
}
