package com.febrie.backend.article.like.web;

import com.febrie.backend.article.application.ArticleIds;
import com.febrie.backend.article.like.application.*;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.shared.error.ApiException;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 身份仅来自Session，目标状态由HTTP方法确定，不信任客户端userId/version。
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "app.like.enabled", havingValue = "true")
public class LikeController {
    private final LikeWriteService writes;

    public LikeController(LikeWriteService writes) {
        this.writes = writes;
    }

    @PutMapping("/api/articles/{id}/like")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LikeWriteService.Accepted like(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
                                          @RequestHeader(value = "X-Operation-Id", required = false) String operation) {
        return set(principal, id, operation, true);
    }

    @DeleteMapping("/api/articles/{id}/like")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LikeWriteService.Accepted unlike(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
                                            @RequestHeader(value = "X-Operation-Id", required = false) String operation) {
        return set(principal, id, operation, false);
    }

    private LikeWriteService.Accepted set(SessionPrincipal principal, String id, String operation, boolean desired) {
        if (principal == null) {
            throw ApiException.unauthorized();
        }
        long article;
        try {
            article = ArticleIds.parse(id);
        } catch (ApiException invalid) {
            throw LikeErrors.invalid();
        }
        return writes.set(principal.userId(), article, desired, operation);
    }
}
