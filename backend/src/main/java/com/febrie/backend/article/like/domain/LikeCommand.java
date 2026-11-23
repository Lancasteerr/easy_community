package com.febrie.backend.article.like.domain;

import java.time.Instant;
import java.util.UUID;

/** 自包含且不可变的命令；同次发布重试必须复用整个对象。 */
public record LikeCommand(Integer schemaVersion, String operationId, Long userId, LikeTarget target,
                          Boolean desiredLiked, String version, Instant occurredAt) {
    public LikeCommand {
        if (!Integer.valueOf(1).equals(schemaVersion) || userId == null || userId <= 0
                || target == null || desiredLiked == null || occurredAt == null) {
            throw new IllegalArgumentException("点赞命令字段缺失或非法");
        }
        operationId = operation(operationId);
        LikeVersion.parse(version);
    }

    /** 文章调用方可以继续用文章 ID 构造领域命令。 */
    public LikeCommand(Integer schemaVersion, String operationId, Long userId, long articleId,
                       Boolean desiredLiked, String version, Instant occurredAt) {
        this(schemaVersion, operationId, userId,
                new LikeTarget(LikeTarget.Type.ARTICLE, articleId), desiredLiked, version, occurredAt);
    }

    public long number() { return LikeVersion.parse(version); }
    public String messageId() { return userId + ":" + target.type() + ":" + target.id() + ":" + version; }

    public static String operation(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("operationId必须为UUID");
        }
        return UUID.fromString(value).toString();
    }
}
