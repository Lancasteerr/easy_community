package com.febrie.backend.article.comment.application.command;

import com.febrie.backend.article.comment.application.CommentErrors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** 一级评论、回复和删除共用结果表，摘要区分操作及原始载荷。 */
final class CommentOperation {
    private CommentOperation() { }

    static String id(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw CommentErrors.invalid();
        }
        return UUID.fromString(value).toString();
    }

    static String submission(CommentInput input) {
        String prefix = input.replyToId() == null ? "create:" : "reply:" + input.replyToId() + ":";
        return digest(prefix + input.content());
    }

    static String deletion(long commentId) { return digest("delete:" + commentId); }

    private static String digest(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("缺少评论请求摘要算法", failure);
        }
    }
}
