package com.febrie.backend.article.comment;

import com.febrie.backend.article.common.ArticleIds;
import com.febrie.backend.article.like.application.LikeWriteService;
import com.febrie.backend.article.like.domain.LikeTarget;
import com.febrie.backend.auth.application.dto.SessionPrincipal;
import com.febrie.backend.shared.error.ApiException;
import com.febrie.backend.user.application.dto.UserActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 仅从会话取用户身份，评论正文通过列表和创建回应展示。 */
@RestController
@RequestMapping("/api/articles/{articleId}/comments")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommentController {
    private final CommentLists lists;
    private final CommentWrites writes;
    private final CommentViews views;
    private final CommentAccess access;
    private final LikeWriteService likes;

    public CommentController(CommentLists lists, CommentWrites writes, CommentViews views,
                             CommentAccess access, LikeWriteService likes) {
        this.lists = lists;
        this.writes = writes;
        this.views = views;
        this.access = access;
        this.likes = likes;
    }

    @GetMapping
    public CommentLists.Response comments(@PathVariable String articleId, @RequestParam(defaultValue = "latest") String sort,
                                          @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String cursor,
                                          @AuthenticationPrincipal SessionPrincipal principal) {
        var ordering = switch (sort) {
            case "latest" -> CommentListKey.Sort.LATEST;
            case "hot" -> CommentListKey.Sort.HOT;
            default -> throw CommentErrors.invalid();
        };
        return lists.page(new CommentListKey(ArticleIds.parse(articleId), 0, ordering), size, cursor, user(principal));
    }

    @GetMapping("/{rootId}/replies")
    public CommentLists.Response replies(@PathVariable String articleId, @PathVariable String rootId,
                                         @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String cursor,
                                         @AuthenticationPrincipal SessionPrincipal principal) {
        return lists.page(new CommentListKey(ArticleIds.parse(articleId), ArticleIds.parse(rootId),
                CommentListKey.Sort.REPLIES), size, cursor, user(principal));
    }

    @PostMapping(consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentViews.Item create(@PathVariable String articleId, @RequestBody JsonNode body,
                                   @AuthenticationPrincipal SessionPrincipal principal) {
        var actor = actor(principal);
        var content = body.path("content");
        var reply = body.path("replyToId");
        if (!body.isObject() || !content.isString() || body.size() != (reply.isMissingNode() ? 1 : 2)
                || (!reply.isMissingNode() && !reply.isNull() && !reply.isString())) {
            throw CommentErrors.invalid();
        }
        Long target = reply.isString() ? ArticleIds.parse(reply.asString()) : null;
        return views.created(writes.create(ArticleIds.parse(articleId), actor, target, content.asString()), principal.username());
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String articleId, @PathVariable String commentId,
                       @AuthenticationPrincipal SessionPrincipal principal) {
        writes.delete(ArticleIds.parse(articleId), ArticleIds.parse(commentId), actor(principal));
    }

    @PutMapping("/{commentId}/like")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LikeWriteService.Accepted like(@PathVariable String articleId, @PathVariable String commentId,
            @RequestHeader(value = "X-Operation-Id", required = false) String operation,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return setLike(articleId, commentId, principal, operation, true);
    }

    @DeleteMapping("/{commentId}/like")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LikeWriteService.Accepted unlike(@PathVariable String articleId, @PathVariable String commentId,
            @RequestHeader(value = "X-Operation-Id", required = false) String operation,
            @AuthenticationPrincipal SessionPrincipal principal) {
        return setLike(articleId, commentId, principal, operation, false);
    }

    private LikeWriteService.Accepted setLike(String articleId, String commentId, SessionPrincipal principal,
                                              String operation, boolean desired) {
        long user = actor(principal).userId();
        long article = ArticleIds.parse(articleId);
        long comment = ArticleIds.parse(commentId);
        access.article(article);
        access.root(article, comment, true);
        return likes.set(user, new LikeTarget(LikeTarget.Type.COMMENT, comment), desired, operation);
    }

    private static Long user(SessionPrincipal principal) {
        return principal == null ? null : principal.userId();
    }

    private static UserActor actor(SessionPrincipal principal) {
        if (principal == null) throw ApiException.unauthorized();
        return new UserActor(principal.userId(), principal.role(), principal.authVersion());
    }
}
