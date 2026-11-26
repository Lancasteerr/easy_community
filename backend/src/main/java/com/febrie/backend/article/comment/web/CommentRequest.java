package com.febrie.backend.article.comment.web;

import com.febrie.backend.article.comment.application.CommentErrors;
import com.febrie.backend.article.comment.application.command.CommentInput;
import com.febrie.backend.article.common.ArticleIds;
import tools.jackson.databind.JsonNode;

/** 发布请求包含纯文字及可选的回复对象。 */
final class CommentRequest {
    private CommentRequest() { }

    static CommentInput read(JsonNode body) {
        var content = body.path("content");
        var reply = body.path("replyToId");
        if (!body.isObject() || !content.isString() || body.size() != (reply.isMissingNode() ? 1 : 2)
                || (!reply.isMissingNode() && !reply.isNull() && !reply.isString())) {
            throw CommentErrors.invalid();
        }
        return new CommentInput(content.asString(), reply.isString() ? ArticleIds.parse(reply.asString()) : null);
    }
}
