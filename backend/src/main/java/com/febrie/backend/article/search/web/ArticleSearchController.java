package com.febrie.backend.article.search.web;

import com.febrie.backend.article.search.application.*;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 静态search路由优先于文章ID路由，游客不创建新的会话。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ArticleSearchController {
    private final ArticleSearchService searches;
    private final ArticleSearchAssembler assembler;
    public ArticleSearchController(ArticleSearchService searches, ArticleSearchAssembler assembler) {
        this.searches = searches; this.assembler = assembler;
    }
    @GetMapping("/api/articles/search")
    public ArticleSearchAssembler.Response search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long tagId, @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return assembler.assemble(searches.page(new ArticleSearchQuery("search", q, tagId, authorId, sort, size), cursor),
                principal == null ? null : principal.userId());
    }
    @GetMapping("/api/articles/feed")
    public ArticleSearchAssembler.Response feed(@RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal SessionPrincipal principal) {
        return assembler.assemble(searches.page(new ArticleSearchQuery("feed", null, null, null, "recommended", size), cursor),
                principal == null ? null : principal.userId());
    }

    /** 本人入口保留独立路由，不绑定请求中的authorId、关键词或状态。 */
    @GetMapping("/api/users/me/articles")
    public ArticleSearchAssembler.Response mine(@RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal SessionPrincipal principal) {
        if (principal == null) { throw com.febrie.backend.shared.error.ApiException.unauthorized(); }
        var query = new ArticleSearchQuery("mine", null, null, principal.userId(), "latest", size);
        return assembler.assemble(searches.page(query, cursor), principal.userId());
    }
}
