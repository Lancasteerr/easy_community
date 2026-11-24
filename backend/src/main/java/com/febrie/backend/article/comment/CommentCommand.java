package com.febrie.backend.article.comment;

import com.febrie.backend.user.application.dto.UserActor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

/** 创建与删除由不同字段表示，摘要绑定实际被执行的请求。 */
record CommentCommand(long articleId, UserActor actor, String operationId, Long commentId, String content) {
    CommentCommand {
        if (articleId <= 0 || actor == null || actor.userId() <= 0 || actor.role() == null) {
            throw CommentErrors.invalid();
        }
        operationId = operation(operationId);
        if (commentId == null) {
            content = CommentWrites.content(content);
        } else if (commentId <= 0 || content != null) {
            throw CommentErrors.invalid();
        }
    }

    String fingerprint() {
        String payload = commentId == null ? "create:" + content : "delete:" + commentId;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少评论请求摘要算法", failure);
        }
    }

    static String operation(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw CommentErrors.invalid();
        }
        return UUID.fromString(value).toString();
    }
}
